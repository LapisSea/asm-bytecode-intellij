package org.objectweb.asm.idea.sync;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.ex.ToolWindowManagerListener;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.idea.OutlineLoader;

import java.util.function.BooleanSupplier;

/**
 * Follow mode: while the ASM tool window is visible, shows the bytecode of whatever the user is editing.
 * The bytecode is reloaded when another file is selected, when the tool window is shown, and when the caret
 * moves into a different class of the same file (inner and anonymous classes have their own class files).
 */
public final class FollowEditor implements Disposable{

	private final Project         project;
	private final Editor          bytecodeEditor;
	private final BooleanSupplier enabled;

	public FollowEditor(Project project, Editor bytecodeEditor, BooleanSupplier enabled){
		this.project = project;
		this.bytecodeEditor = bytecodeEditor;
		this.enabled = enabled;

		var bus = project.getMessageBus().connect(this);
		bus.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener(){
			@Override
			public void selectionChanged(@NotNull FileEditorManagerEvent event){
				followSelectedEditor();
			}
		});
		bus.subscribe(ToolWindowManagerListener.TOPIC, new ToolWindowManagerListener(){
			@Override
			public void toolWindowShown(@NotNull ToolWindow toolWindow){
				if(OutlineLoader.TOOL_WINDOW_ID.equals(toolWindow.getId())) followSelectedEditor();
			}
		});
		EditorFactory.getInstance().getEventMulticaster().addCaretListener(new CaretListener(){
			@Override
			public void caretPositionChanged(@NotNull CaretEvent event){
				onCaretMoved(event.getEditor());
			}
		}, this);
	}

	private boolean isActive(){
		if(project.isDisposed() || !enabled.getAsBoolean() || DumbService.isDumb(project)) return false;
		ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(OutlineLoader.TOOL_WINDOW_ID);
		return toolWindow != null && toolWindow.isVisible();
	}

	/**
	 * Shows the bytecode of the selected editor's file, unless it is already shown.
	 */
	public void followSelectedEditor(){
		if(!isActive()) return;
		var fem = FileEditorManager.getInstance(project);

		Editor      editor = fem.getSelectedTextEditor();
		VirtualFile file;
		if(editor != null){
			file = FileDocumentManager.getInstance().getFile(editor.getDocument());
		}else{
			var selected = fem.getSelectedFiles();
			file = selected.length == 0? null : selected[0];
		}
		if(file == null || !OutlineLoader.canLoad(project, file)) return;

		int offset = editor == null? -1 : editor.getCaretModel().getOffset();
		load(file, offset);
	}

	private void onCaretMoved(Editor editor){
		if(editor == bytecodeEditor || editor.getProject() != project || !isActive()) return;
		// Background editors, previews etc. are not what the user is looking at
		if(FileEditorManager.getInstance(project).getSelectedTextEditor() != editor) return;

		var file = FileDocumentManager.getInstance().getFile(editor.getDocument());
		if(file == null || "class".equals(file.getExtension())) return;
		// Without a committed PSI the caret offset could point into the wrong class
		if(!PsiDocumentManager.getInstance(project).isCommitted(editor.getDocument())) return;

		load(file, editor.getCaretModel().getOffset());
	}

	private void load(VirtualFile file, int offset){
		var loader = OutlineLoader.getInstance(project);
		var psi    = PsiManager.getInstance(project).findFile(file);
		if(psi == null) return;

		String className = "class".equals(file.getExtension())? null : OutlineLoader.classNameAt(psi, offset);
		// With no class at the caret (imports, a class file...) any class of the file already shown is fine
		if(loader.isRequested(file, className)) return;

		loader.load(file, offset, false);
	}

	@Override
	public void dispose(){ }
}
