package org.objectweb.asm.idea.highlight;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileTypes.LanguageFileType;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

/**
 * File type of the bytecode view. Registered without extensions, so it never claims real files.
 */
public final class BytecodeFileType extends LanguageFileType{
	public static final BytecodeFileType INSTANCE = new BytecodeFileType();

	private BytecodeFileType(){
		super(BytecodeLanguage.INSTANCE);
	}

	@Override
	public @NotNull String getName(){
		return "ASM Bytecode";
	}

	@Override
	public @NotNull String getDescription(){
		return "Bytecode as printed by ASM";
	}

	@Override
	public @NotNull String getDefaultExtension(){
		return "";
	}

	@Override
	public Icon getIcon(){
		return AllIcons.FileTypes.JavaClass;
	}
}
