package org.objectweb.asm.idea.highlight;

import com.intellij.lang.Language;

/**
 * The text ASM's Textifier prints. Only used for highlighting, there is no parser.
 */
public final class BytecodeLanguage extends Language{
	public static final BytecodeLanguage INSTANCE = new BytecodeLanguage();
	
	private BytecodeLanguage(){
		super("ASMBytecode");
	}
	
	@Override
	public String getDisplayName(){
		return "ASM Bytecode";
	}
}
