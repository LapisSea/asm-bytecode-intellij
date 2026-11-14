package org.objectweb.asm.idea.highlight;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.options.colors.AttributesDescriptor;
import com.intellij.openapi.options.colors.ColorDescriptor;
import com.intellij.openapi.options.colors.ColorSettingsPage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.idea.sync.CaretSync;

import javax.swing.*;
import java.util.Map;

import static org.objectweb.asm.idea.highlight.BytecodeHighlighter.*;

/**
 * Settings | Editor | Color Scheme | ASM Bytecode
 */
public final class BytecodeColorSettingsPage implements ColorSettingsPage{
	
	private static final AttributesDescriptor[] DESCRIPTORS = {
		new AttributesDescriptor("Instructions//Opcode", OPCODE),
		new AttributesDescriptor("Instructions//Label", LABEL),
		new AttributesDescriptor("Instructions//Not an instruction (LINENUMBER, FRAME, MAXSTACK...)", DIRECTIVE),
		new AttributesDescriptor("Types//Class name", CLASS_NAME),
		new AttributesDescriptor("Types//Package, and L ; around class names", PACKAGE),
		new AttributesDescriptor("Types//Primitive type", PRIMITIVE),
		new AttributesDescriptor("Members//Method call", METHOD_CALL),
		new AttributesDescriptor("Members//Method declaration", METHOD_DECL),
		new AttributesDescriptor("Members//Field", FIELD),
		new AttributesDescriptor("Members//Local variable", LOCAL_VAR),
		new AttributesDescriptor("Keyword and access flag", KEYWORD),
		new AttributesDescriptor("Annotation", ANNOTATION),
		new AttributesDescriptor("String", STRING),
		new AttributesDescriptor("Number", NUMBER),
		new AttributesDescriptor("Comment", COMMENT),
		new AttributesDescriptor("Braces and Operators//Parentheses", PARENS),
		new AttributesDescriptor("Braces and Operators//Brackets", BRACKETS),
		new AttributesDescriptor("Braces and Operators//Braces", BRACES),
		new AttributesDescriptor("Braces and Operators//Punctuation", PUNCTUATION),
		new AttributesDescriptor("Code linked to the caret in the source", CaretSync.SYNC_LINE),
		};
	
	@Override
	public @Nullable Icon getIcon(){
		return BytecodeFileType.INSTANCE.getIcon();
	}
	
	@Override
	public @NotNull SyntaxHighlighter getHighlighter(){
		return new BytecodeHighlighter();
	}
	
	@Override
	public @NotNull String getDemoText(){
		return """
			// class version 69.0 (69)
			// access flags 0x21
			public class com/example/Greeter extends java/lang/Object {
			
			  // access flags 0x2
			  private I count
			
			  // access flags 0x1
			  @Ljava/lang/Deprecated;() // invisible
			  public greet(Ljava/lang/String;)Ljava/lang/String; throws java/io/IOException
			   L0
			    LINENUMBER 12 L0
			    ALOAD 0
			    GETFIELD com/example/Greeter.count : I
			    IFLE L1
			<sync>    ALOAD 1
			    INVOKEDYNAMIC makeConcatWithConstants(Ljava/lang/String;)Ljava/lang/String; [</sync>
			      // handle kind 0x6 : INVOKESTATIC
			      java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;
			      // arguments:
			      "Hello \\u0001!"
			    ]
			    ARETURN
			   L1
			   FRAME SAME
			    NEW java/lang/IllegalStateException
			    DUP
			    LDC 2L
			    INVOKESPECIAL java/lang/IllegalStateException.<init> ()V
			    ATHROW
			   L2
			    LOCALVARIABLE this Lcom/example/Greeter; L0 L2 0
			    LOCALVARIABLE name Ljava/lang/String; L0 L2 1
			    MAXSTACK = 2
			    MAXLOCALS = 2
			}
			""";
	}
	
	@Override
	public @Nullable Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap(){
		return Map.of("sync", CaretSync.SYNC_LINE);
	}
	
	@Override
	public AttributesDescriptor @NotNull [] getAttributeDescriptors(){
		return DESCRIPTORS;
	}
	
	@Override
	public ColorDescriptor @NotNull [] getColorDescriptors(){
		return ColorDescriptor.EMPTY_ARRAY;
	}
	
	@Override
	public @NotNull String getDisplayName(){
		return "ASM Bytecode";
	}
}
