package org.objectweb.asm.idea;

import com.intellij.execution.filters.LineNumbersMapping;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.ide.util.JavaAnonymousClassesHelper;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.CompilerModuleExtension;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.psi.PsiAnonymousClass;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassOwner;
import com.intellij.psi.PsiCompiledElement;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.util.ClassUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ui.LightColors;
import com.intellij.ui.awt.RelativePoint;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.idea.config.ASMPluginComponent;
import org.objectweb.asm.idea.sync.InsnMapping;
import org.objectweb.asm.idea.sync.RecordingTextifier;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntUnaryOperator;

/**
 * Given a java file (or any file which generates classes), tries to locate a .class file. If the compilation state is
 * not up to date, performs an automatic compilation of the class. If the .class file can be located, generates bytecode
 * instructions for the class and ASMified code, and displays them into the tool window.
 *
 * @author Cédric Champeau
 * @author Thiakil (December 2017)
 */
@Service(Service.Level.PROJECT)
public final class OutlineLoader{
	
	public static final String TOOL_WINDOW_ID = "ASM";
	
	private static final Logger LOG = Logger.getInstance(OutlineLoader.class);
	
	/**
	 * @param generation    loads are numbered so a slow, older load never overwrites a newer one
	 * @param userTriggered explicit requests activate the tool window and report failures, follow mode loads do neither
	 */
	private record Request(long generation, boolean userTriggered){ }
	
	private final Project    project;
	private final AtomicLong generation = new AtomicLong();
	
	private volatile @Nullable VirtualFile requestedFile;
	private volatile @Nullable String      requestedClass;
	
	public OutlineLoader(Project project){
		this.project = project;
	}
	
	public static OutlineLoader getInstance(Project project){
		return project.getService(OutlineLoader.class);
	}
	
	public static boolean canLoad(Project project, VirtualFile file){
		return file != null && file.isValid() && PsiManager.getInstance(project).findFile(file) instanceof PsiClassOwner;
	}
	
	/**
	 * @return true if the last load was for this file, and for this class if one is given
	 */
	public boolean isRequested(VirtualFile file, @Nullable String className){
		return file.equals(requestedFile) && (className == null || className.equals(requestedClass));
	}
	
	/**
	 * Loads the class at the caret of a source file (or the file itself if it is a class file). Must be called on the EDT.
	 *
	 * @param caretOffset selects which class of the file to show, -1 if unknown
	 */
	public void load(VirtualFile virtualFile, int caretOffset, boolean userTriggered){
		final PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
		if(!(psiFile instanceof PsiClassOwner)) return;
		
		final var request = new Request(generation.incrementAndGet(), userTriggered);
		final var isClass = "class".equals(virtualFile.getExtension());
		requestedFile = virtualFile;
		requestedClass = isClass? null : classNameAt(psiFile, caretOffset);
		
		final Application app = ApplicationManager.getApplication();
		if(isClass){
			// The editor shows decompiled code, synced through the decompiler's line mapping
			app.executeOnPooledThread(() -> decode(virtualFile, virtualFile, request));
			return;
		}
		if(!virtualFile.isInLocalFileSystem() && !virtualFile.isWritable()){
			// probably a source file in a library
			app.executeOnPooledThread(() -> decode(findLibraryClassFile(psiFile, caretOffset), virtualFile, request));
			return;
		}
		
		final Module module = ModuleUtil.findModuleForPsiElement(psiFile);
		if(module == null) return;
		final CompilerModuleExtension cme             = CompilerModuleExtension.getInstance(module);
		final CompilerManager         compilerManager = CompilerManager.getInstance(project);
		final VirtualFile[]           files           = {virtualFile};
		
		final var fdm = FileDocumentManager.getInstance();
		if(userTriggered){
			app.runWriteAction(fdm::saveAllDocuments);
		}else{
			// Only what is needed to compile this file, follow mode should not have side effects on other files
			var doc = fdm.getCachedDocument(virtualFile);
			if(doc != null && fdm.isDocumentUnsaved(doc)) app.runWriteAction(() -> fdm.saveDocument(doc));
		}
		
		app.executeOnPooledThread(() -> {
			if(isStale(request)) return;
			var classFile         = new CompletableFuture<VirtualFile>();
			var outputDirectories = cme == null? null : cme.getOutputRoots(true);
			if(outputDirectories != null && compilerManager.isUpToDate(compilerManager.createFilesCompileScope(files))){
				classFile.complete(findClassFile(outputDirectories, psiFile, caretOffset));
			}else{
				app.invokeLater(() -> compilerManager.compile(files, (aborted, errors, warnings, compileContext) -> {
					var found = errors == 0 && cme != null? findClassFile(cme.getOutputRoots(true), psiFile, caretOffset) : null;
					app.executeOnPooledThread(() -> classFile.complete(found));
				}));
			}
			classFile.thenAccept(file -> decode(file, virtualFile, request));
		});
	}
	
	private boolean isStale(Request request){
		return request.generation() != generation.get();
	}
	
	/**
	 * @return the JVM name of the innermost class containing the offset, or null if there is none
	 */
	public static @Nullable String classNameAt(PsiFile psiFile, int offset){
		return ApplicationManager.getApplication().runReadAction((Computable<String>)() -> {
			var psiClass = findClassAt(psiFile, offset);
			return psiClass == null? null : getJVMClassName(psiClass);
		});
	}
	
	private static @Nullable PsiClass findClassAt(PsiFile psiFile, int offset){
		if(offset<0 || !psiFile.isValid()) return null;
		PsiElement elem = psiFile.findElementAt(offset);
		while(elem != null){
			if(elem instanceof PsiClass c && !(c instanceof PsiTypeParameter)) return c;
			elem = elem.getParent();
		}
		return null;
	}
	
	private static @Nullable VirtualFile findLibraryClassFile(PsiFile psiFile, int caretOffset){
		return ApplicationManager.getApplication().runReadAction((Computable<VirtualFile>)() -> {
			if(!psiFile.isValid()) return null;
			PsiClass aClass = findClassAt(psiFile, caretOffset);
			if(aClass != null){
				String jvmClassName = getJVMClassName(aClass);
				if(jvmClassName != null){
					String           relativePath = jvmClassName.replace('.', '/') + ".class";
					ProjectFileIndex index        = ProjectFileIndex.getInstance(aClass.getProject());
					
					PsiElement originalClass = aClass.getOriginalElement();
					if(aClass instanceof PsiAnonymousClass){
						originalClass = Objects.requireNonNull(PsiTreeUtil.getParentOfType(aClass, PsiClass.class)).getOriginalElement();
					}
					if(originalClass instanceof PsiCompiledElement){
						// compiled class; looking for a .class file inside a library
						VirtualFile file = originalClass.getContainingFile().getVirtualFile();
						if(file != null){
							VirtualFile classRoot = index.getClassRootForFile(file);
							if(classRoot != null){
								VirtualFile classFile = classRoot.findFileByRelativePath(relativePath);
								if(classFile != null) return classFile;
							}
						}
					}
				}
			}
			//fallback
			final PsiClass[] psiClasses = ((PsiClassOwner)psiFile).getClasses();
			if(psiClasses.length>0){
				return psiClasses[0].getOriginalElement().getContainingFile().getVirtualFile();
			}
			return null;
		});
	}
	
	private static @Nullable VirtualFile findClassFile(VirtualFile[] outputDirectories, PsiFile psiFile, int caretOffset){
		return ApplicationManager.getApplication().runReadAction((Computable<VirtualFile>)() -> {
			if(outputDirectories == null || !(psiFile instanceof PsiClassOwner psiJavaFile) || !psiFile.isValid()) return null;
			PsiClass psiClass = findClassAt(psiFile, caretOffset);
			if(psiClass != null){
				return getClassFile(outputDirectories, psiClass);
			}
			for(PsiClass c : psiJavaFile.getClasses()){
				final VirtualFile file = getClassFile(outputDirectories, c);
				if(file != null) return file;
			}
			return null;
		});
	}
	
	private static @Nullable VirtualFile getClassFile(VirtualFile[] outputDirectories, @NotNull PsiClass psiClass){
		String jvmClassName = getJVMClassName(psiClass);
		if(jvmClassName == null) jvmClassName = "";
		String classFileName = jvmClassName.replace('.', '/') + ".class";
		for(VirtualFile outputDirectory : outputDirectories){
			final VirtualFile file = outputDirectory.findFileByRelativePath(classFileName);
			if(file != null && file.exists()) return file;
		}
		return null;
	}
	
	public static @Nullable String getJVMClassName(PsiClass aClass){
		if(!(aClass instanceof PsiAnonymousClass)){
			return ClassUtil.getJVMClassName(aClass);
		}
		PsiClass containingClass = PsiTreeUtil.getParentOfType(aClass, PsiClass.class);
		if(containingClass != null){
			return getJVMClassName(containingClass) + JavaAnonymousClassesHelper.getName((PsiAnonymousClass)aClass);
		}
		return null;
	}
	
	/**
	 * Called off the EDT.
	 *
	 * @param sourceFile the file the class was compiled from, or null if it is not known or its lines do not match
	 */
	private void decode(@Nullable VirtualFile file, @Nullable VirtualFile sourceFile, Request request){
		final Application app = ApplicationManager.getApplication();
		if(isStale(request)) return;
		if(file == null){
			if(request.userTriggered()) app.invokeLater(() -> app.runWriteAction(this::showNotFound));
			return;
		}
		final ASMPluginComponent config = project.getService(ASMPluginComponent.class);
		
		Executor exec = r -> app.invokeLater(() -> {
			if(isStale(request) || project.isDisposed()) return;
			app.runWriteAction(r);
		});
		
		ClassReader reader;
		try{
			file.refresh(false, false);
			reader = new ClassReader(file.contentsToByteArray());
		}catch(IOException e){
			return;
		}
		
		// A class file is its own source when it is shown decompiled, but with lines of its own
		IntUnaryOperator lineMap = IntUnaryOperator.identity();
		if(sourceFile != null && isClassFile(sourceFile)){
			lineMap = decompiledLines(sourceFile);
			if(lineMap == null) sourceFile = null;
		}
		final IntUnaryOperator lines  = lineMap == null? IntUnaryOperator.identity() : lineMap;
		final VirtualFile      source = sourceFile;
		
		record Outline(String code, @Nullable RecordingTextifier.Result printed, @Nullable InsnMapping mapping){ }
		CompletableFuture
			.supplyAsync(() -> {
				var textifier = new RecordingTextifier(lines);
				reader.accept(new TraceClassVisitor(null, textifier, null), computeFlags(config));
				var printed = textifier.print();
				return new Outline(printed.text(), printed, buildInsnMapping(source, reader.getClassName(), printed));
			})
			.exceptionally(e -> new Outline(mapVisitErr(e, config), null, null))
			.thenAcceptAsync(outline -> {
				BytecodeOutline.getInstance(project).setCode(file, source, outline.code(), outline.printed(), outline.mapping(), lines);
				afterBytecodeShown(request);
			}, exec);
		
		CompletableFuture
			.supplyAsync(() -> {
				var stringWriter = new StringWriter();
				reader.accept(
					new TraceClassVisitor(
						null,
						new CustomASMifier(),
						new PrintWriter(stringWriter)
					),
					computeFlags(config)
				);
				return app.runReadAction((Computable<String>)() -> {
					var asmfiedFile = PsiFileFactory.getInstance(project).createFileFromText(
						"asm.java", JavaFileType.INSTANCE, stringWriter.toString()
					);
					CodeStyleManager.getInstance(project).reformatText(asmfiedFile, 0, asmfiedFile.getTextLength());
					return asmfiedFile.getText();
				});
			})
			.exceptionally(e -> mapVisitErr(e, config))
			.thenAcceptAsync(code -> BytecodeASMified.getInstance(project).setCode(file, code), exec);
		
		CompletableFuture
			.supplyAsync(() -> {
				var stringWriter = new StringWriter();
				reader.accept(
					new TraceClassVisitor(
						null,
						new GroovifiedTextifier(config.getCodeStyle()),
						new PrintWriter(stringWriter)
					),
					ClassReader.SKIP_FRAMES|ClassReader.SKIP_DEBUG
				);
				return stringWriter.toString();
			})
			.exceptionally(e -> mapVisitErr(e, config))
			.thenAcceptAsync(code -> GroovifiedView.getInstance(project).setCode(file, code), exec);
	}
	
	/**
	 * Per instruction source mapping. Best effort: any failure just means falling back to line matching.
	 */
	private @Nullable InsnMapping buildInsnMapping(@Nullable VirtualFile sourceFile, String className, RecordingTextifier.Result printed){
		if(sourceFile == null) return null;
		try{
			return ApplicationManager.getApplication().runReadAction((Computable<InsnMapping>)() -> {
				if(project.isDisposed() || !sourceFile.isValid()) return null;
				var psiFile = PsiManager.getInstance(project).findFile(sourceFile);
				// Decompiled code: the Java file behind the text shown
				if(psiFile instanceof PsiCompiledElement compiled && compiled.getMirror() instanceof PsiFile mirror) psiFile = mirror;
				var document = FileDocumentManager.getInstance().getDocument(sourceFile);
				if(psiFile == null || document == null) return null;
				return InsnMapping.build(psiFile, document, className, printed);
			});
		}catch(ProcessCanceledException e){
			throw e;
		}catch(Throwable e){
			LOG.warn("Could not map instructions of " + className + " to source", e);
			return null;
		}
	}
	
	private static boolean isClassFile(VirtualFile file){
		return "class".equals(file.getExtension());
	}
	
	/**
	 * The decompiler records which lines of its text each line of the class file became.
	 *
	 * @return class file line to decompiled line, both 0-based; null if the decompiler gave no mapping
	 */
	private static @Nullable IntUnaryOperator decompiledLines(VirtualFile classFile){
		return ApplicationManager.getApplication().runReadAction((Computable<IntUnaryOperator>)() -> {
			// Decompiles if that has not happened yet, which is when the mapping is made
			if(FileDocumentManager.getInstance().getDocument(classFile) == null) return null;
			var mapping = classFile.getUserData(LineNumbersMapping.LINE_NUMBERS_MAPPING_KEY);
			if(mapping == null) return null;
			return line -> {
				int mapped = mapping.bytecodeToSource(line + 1);
				return mapped>0? mapped - 1 : -1;
			};
		});
	}
	
	private void afterBytecodeShown(Request request){
		var outline = BytecodeOutline.getInstance(project);
		if(request.userTriggered()){
			ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
			// Sync once the tool window is laid out, otherwise there is nothing to scroll
			if(toolWindow != null) toolWindow.activate(outline::syncWithSourceCaret);
		}else{
			outline.syncWithSourceCaret();
		}
	}
	
	static String mapVisitErr(Throwable e, ASMPluginComponent config){
		if(e instanceof CompletionException ce && ce.getCause() != null) e = ce.getCause();
		var printw = new StringWriter();
		e.printStackTrace(new PrintWriter(printw));
		return "/*\n" +
		       "Something went wrong! Try enabling a skip option in the configuration. Current config:\n" +
		       config + "\n" +
		       "\n" +
		       "Stacktrace:\n" +
		       printw.toString().replace("\r", "") + "\n" +
		       "*/";
	}
	
	static int computeFlags(ASMPluginComponent config){
		int flags = 0;
		if(config.isSkipDebug()) flags = flags|ClassReader.SKIP_DEBUG;
		if(config.isSkipFrames()) flags = flags|ClassReader.SKIP_FRAMES;
		if(config.isExpandFrames()) flags = flags|ClassReader.EXPAND_FRAMES;
		if(config.isSkipCode()) flags = flags|ClassReader.SKIP_CODE;
		return flags;
	}
	
	private void showNotFound(){
		BytecodeOutline.getInstance(project).setCode(null, "");
		BytecodeASMified.getInstance(project).setCode(null, "");
		GroovifiedView.getInstance(project).setCode(null, "");
		ApplicationManager.getApplication().invokeLater(() -> {
			Balloon balloon = JBPopupFactory.getInstance()
			                                .createHtmlTextBalloonBuilder(Constants.NO_CLASS_FOUND, null, LightColors.RED, null)
			                                .setHideOnAction(true)
			                                .setHideOnClickOutside(true)
			                                .setHideOnKeyOutside(true)
			                                .createBalloon();
			StatusBar statusBar = WindowManager.getInstance().getStatusBar(project);
			balloon.show(RelativePoint.getCenterOf(statusBar.getComponent()), Balloon.Position.above);
		});
	}
}
