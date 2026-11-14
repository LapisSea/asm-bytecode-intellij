package org.objectweb.asm.idea;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * A "Bytecode" tab next to the decompiled "Text" tab of class files.
 */
public final class ClassFileEditorProvider implements FileEditorProvider, DumbAware{
	
	@Override
	public boolean accept(@NotNull Project project, @NotNull VirtualFile file){
		return !file.isDirectory() && "class".equals(file.getExtension());
	}
	
	@Override
	public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file){
		return new ClassFileEditor(project, file);
	}
	
	@Override
	public @NotNull String getEditorTypeId(){
		return "asm-bytecode";
	}
	
	@Override
	public @NotNull FileEditorPolicy getPolicy(){
		return FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR;
	}
}
