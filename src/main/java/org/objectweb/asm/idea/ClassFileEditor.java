package org.objectweb.asm.idea;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.ui.OnePixelSplitter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.idea.config.ASMPluginComponent;
import org.objectweb.asm.idea.docs.InsnDocPanel;
import org.objectweb.asm.idea.highlight.BytecodeFileType;
import org.objectweb.asm.idea.nav.BytecodeEditorSupport;
import org.objectweb.asm.idea.sync.RecordingTextifier;
import org.objectweb.asm.util.TraceClassVisitor;

import javax.swing.JComponent;
import java.beans.PropertyChangeListener;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A class file's bytecode as the main editor shows it: read only, with the highlighting, navigation, usages,
 * folding and instruction docs of the tool window.
 */
final class ClassFileEditor extends UserDataHolderBase implements FileEditor{

	private final Project               project;
	private final VirtualFile           file;
	private final Editor                editor;
	private final InsnDocPanel          docPanel;
	private final BytecodeEditorSupport support;
	private final OnePixelSplitter      component;
	private final AtomicInteger         generation  = new AtomicInteger();
	private       RecordingTextifier.Printed[] byLine = new RecordingTextifier.Printed[0];
	private       boolean               disposed;

	ClassFileEditor(Project project, VirtualFile file){
		this.project = project;
		this.file = file;
		var factory = EditorFactory.getInstance();
		editor = factory.createEditor(factory.createDocument(""), project, BytecodeFileType.INSTANCE, true);
		editor.getSettings().setFoldingOutlineShown(true);
		support = new BytecodeEditorSupport(project, editor, name -> null);
		Disposer.register(this, support);
		docPanel = new InsnDocPanel(project);
		Disposer.register(this, docPanel);

		component = new OnePixelSplitter(true, "asm.bytecode.classFileDocs", 0.7f);
		component.setFirstComponent(editor.getComponent());
		if(config().isInstructionDocs()) component.setSecondComponent(docPanel);

		editor.getCaretModel().addCaretListener(new CaretListener(){
			@Override
			public void caretPositionChanged(@NotNull CaretEvent event){
				showDocs();
			}
		}, this);
		// Recompiled or replaced on disk
		project.getMessageBus().connect(this).subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener(){
			@Override
			public void after(@NotNull List<? extends @NotNull VFileEvent> events){
				for(var e : events){
					if(file.equals(e.getFile())){
						load();
						return;
					}
				}
			}
		});
		load();
	}

	private ASMPluginComponent config(){
		return project.getService(ASMPluginComponent.class);
	}

	private void load(){
		int gen    = generation.incrementAndGet();
		var config = config();
		ApplicationManager.getApplication().executeOnPooledThread(() -> {
			String                    text;
			RecordingTextifier.Result printed = null;
			try{
				var reader    = new ClassReader(file.contentsToByteArray());
				var textifier = new RecordingTextifier();
				reader.accept(new TraceClassVisitor(null, textifier, null), OutlineLoader.computeFlags(config));
				printed = textifier.print();
				text = printed.text();
			}catch(Throwable e){
				text = OutlineLoader.mapVisitErr(e, config);
			}
			var t = text;
			var p = printed;
			ApplicationManager.getApplication().invokeLater(() -> {
				if(disposed || gen != generation.get()) return;
				show(t, p);
			});
		});
	}

	private void show(String text, @Nullable RecordingTextifier.Result printed){
		int caret = editor.getCaretModel().getOffset();
		ApplicationManager.getApplication().runWriteAction(() -> {
			var doc = editor.getDocument();
			doc.setReadOnly(false);
			doc.setText(text);
			doc.setReadOnly(true);
		});
		byLine = BytecodeOutline.indexByLine(printed);
		support.updateFolding(printed);
		editor.getCaretModel().moveToOffset(Math.min(caret, text.length()));
		showDocs();
	}

	private void showDocs(){
		if(component.getSecondComponent() == null) return;
		int line = editor.getCaretModel().getLogicalPosition().line;
		docPanel.show(line<byLine.length? byLine[line] : null);
	}

	@Override
	public @NotNull JComponent getComponent(){
		return component;
	}

	@Override
	public @Nullable JComponent getPreferredFocusedComponent(){
		return editor.getContentComponent();
	}

	@Override
	public @NotNull String getName(){
		return "Bytecode";
	}

	@Override
	public @NotNull VirtualFile getFile(){
		return file;
	}

	@Override
	public void setState(@NotNull FileEditorState state){ }

	@Override
	public boolean isModified(){
		return false;
	}

	@Override
	public boolean isValid(){
		return file.isValid();
	}

	@Override
	public void addPropertyChangeListener(@NotNull PropertyChangeListener listener){ }

	@Override
	public void removePropertyChangeListener(@NotNull PropertyChangeListener listener){ }

	@Override
	public void dispose(){
		disposed = true;
		EditorFactory.getInstance().releaseEditor(editor);
	}
}
