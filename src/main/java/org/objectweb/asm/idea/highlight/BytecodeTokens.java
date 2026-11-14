package org.objectweb.asm.idea.highlight;

import com.intellij.psi.tree.IElementType;

public final class BytecodeTokens{
	private BytecodeTokens(){ }

	private static IElementType token(String name){
		return new IElementType(name, BytecodeLanguage.INSTANCE);
	}

	public static final IElementType COMMENT     = token("COMMENT");
	public static final IElementType STRING      = token("STRING");
	public static final IElementType NUMBER      = token("NUMBER");
	/** Instruction mnemonic */
	public static final IElementType OPCODE      = token("OPCODE");
	/** Lines that are not instructions: LINENUMBER, LOCALVARIABLE, FRAME, MAXSTACK, INNERCLASS... */
	public static final IElementType DIRECTIVE   = token("DIRECTIVE");
	/** Access flags and the like: public, static, class, extends... */
	public static final IElementType KEYWORD     = token("KEYWORD");
	public static final IElementType LABEL       = token("LABEL");
	/** Package part of a class name, and the L and ; around it in descriptors */
	public static final IElementType PACKAGE     = token("PACKAGE");
	public static final IElementType CLASS_NAME  = token("CLASS_NAME");
	/** Primitive types in descriptors and frames: I, J, V... */
	public static final IElementType PRIMITIVE   = token("PRIMITIVE");
	public static final IElementType METHOD_CALL = token("METHOD_CALL");
	public static final IElementType METHOD_DECL = token("METHOD_DECL");
	public static final IElementType FIELD       = token("FIELD");
	public static final IElementType LOCAL_VAR   = token("LOCAL_VAR");
	public static final IElementType ANNOTATION  = token("ANNOTATION");
	public static final IElementType PARENS      = token("PARENS");
	public static final IElementType BRACKETS    = token("BRACKETS");
	public static final IElementType BRACES      = token("BRACES");
	public static final IElementType PUNCTUATION = token("PUNCTUATION");
	public static final IElementType IDENTIFIER  = token("IDENTIFIER");
}
