package org.objectweb.asm.idea.highlight;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

import static com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey;

/**
 * Colors default to the closest Java equivalents, so they follow the color scheme.
 */
public final class BytecodeHighlighter extends SyntaxHighlighterBase{
	
	public static final TextAttributesKey OPCODE      = createTextAttributesKey("ASM_BYTECODE_OPCODE", DefaultLanguageHighlighterColors.KEYWORD);
	public static final TextAttributesKey DIRECTIVE   = createTextAttributesKey("ASM_BYTECODE_DIRECTIVE", DefaultLanguageHighlighterColors.METADATA);
	public static final TextAttributesKey KEYWORD     = createTextAttributesKey("ASM_BYTECODE_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD);
	public static final TextAttributesKey LABEL       = createTextAttributesKey("ASM_BYTECODE_LABEL", DefaultLanguageHighlighterColors.CONSTANT);
	public static final TextAttributesKey PACKAGE     = createTextAttributesKey("ASM_BYTECODE_PACKAGE", DefaultLanguageHighlighterColors.LINE_COMMENT);
	public static final TextAttributesKey CLASS_NAME  = createTextAttributesKey("ASM_BYTECODE_CLASS_NAME", DefaultLanguageHighlighterColors.CLASS_REFERENCE);
	public static final TextAttributesKey PRIMITIVE   = createTextAttributesKey("ASM_BYTECODE_PRIMITIVE", DefaultLanguageHighlighterColors.KEYWORD);
	public static final TextAttributesKey METHOD_CALL = createTextAttributesKey("ASM_BYTECODE_METHOD_CALL", DefaultLanguageHighlighterColors.FUNCTION_CALL);
	public static final TextAttributesKey METHOD_DECL = createTextAttributesKey("ASM_BYTECODE_METHOD_DECLARATION", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION);
	public static final TextAttributesKey FIELD       = createTextAttributesKey("ASM_BYTECODE_FIELD", DefaultLanguageHighlighterColors.INSTANCE_FIELD);
	public static final TextAttributesKey LOCAL_VAR   = createTextAttributesKey("ASM_BYTECODE_LOCAL_VARIABLE", DefaultLanguageHighlighterColors.LOCAL_VARIABLE);
	public static final TextAttributesKey ANNOTATION  = createTextAttributesKey("ASM_BYTECODE_ANNOTATION", DefaultLanguageHighlighterColors.METADATA);
	public static final TextAttributesKey STRING      = createTextAttributesKey("ASM_BYTECODE_STRING", DefaultLanguageHighlighterColors.STRING);
	public static final TextAttributesKey NUMBER      = createTextAttributesKey("ASM_BYTECODE_NUMBER", DefaultLanguageHighlighterColors.NUMBER);
	public static final TextAttributesKey COMMENT     = createTextAttributesKey("ASM_BYTECODE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT);
	public static final TextAttributesKey PARENS      = createTextAttributesKey("ASM_BYTECODE_PARENTHESES", DefaultLanguageHighlighterColors.PARENTHESES);
	public static final TextAttributesKey BRACKETS    = createTextAttributesKey("ASM_BYTECODE_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS);
	public static final TextAttributesKey BRACES      = createTextAttributesKey("ASM_BYTECODE_BRACES", DefaultLanguageHighlighterColors.BRACES);
	public static final TextAttributesKey PUNCTUATION = createTextAttributesKey("ASM_BYTECODE_PUNCTUATION", DefaultLanguageHighlighterColors.OPERATION_SIGN);
	
	private static final Map<IElementType, TextAttributesKey> KEYS = Map.ofEntries(
		Map.entry(BytecodeTokens.OPCODE, OPCODE),
		Map.entry(BytecodeTokens.DIRECTIVE, DIRECTIVE),
		Map.entry(BytecodeTokens.KEYWORD, KEYWORD),
		Map.entry(BytecodeTokens.LABEL, LABEL),
		Map.entry(BytecodeTokens.PACKAGE, PACKAGE),
		Map.entry(BytecodeTokens.CLASS_NAME, CLASS_NAME),
		Map.entry(BytecodeTokens.PRIMITIVE, PRIMITIVE),
		Map.entry(BytecodeTokens.METHOD_CALL, METHOD_CALL),
		Map.entry(BytecodeTokens.METHOD_DECL, METHOD_DECL),
		Map.entry(BytecodeTokens.FIELD, FIELD),
		Map.entry(BytecodeTokens.LOCAL_VAR, LOCAL_VAR),
		Map.entry(BytecodeTokens.ANNOTATION, ANNOTATION),
		Map.entry(BytecodeTokens.STRING, STRING),
		Map.entry(BytecodeTokens.NUMBER, NUMBER),
		Map.entry(BytecodeTokens.COMMENT, COMMENT),
		Map.entry(BytecodeTokens.PARENS, PARENS),
		Map.entry(BytecodeTokens.BRACKETS, BRACKETS),
		Map.entry(BytecodeTokens.BRACES, BRACES),
		Map.entry(BytecodeTokens.PUNCTUATION, PUNCTUATION)
	);
	
	@Override
	public @NotNull Lexer getHighlightingLexer(){
		return new BytecodeLexer();
	}
	
	@Override
	public TextAttributesKey @NotNull [] getTokenHighlights(IElementType tokenType){
		return pack(KEYS.get(tokenType));
	}
}
