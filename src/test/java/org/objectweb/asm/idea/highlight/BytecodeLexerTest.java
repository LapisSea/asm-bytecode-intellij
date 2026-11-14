package org.objectweb.asm.idea.highlight;

import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.Printer;
import org.objectweb.asm.util.TraceClassVisitor;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.objectweb.asm.idea.highlight.BytecodeTokens.*;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class BytecodeLexerTest{
	
	private record Tok(IElementType type, String text){
		@Override
		public String toString(){ return type + ":" + text; }
	}
	
	private static List<Tok> lex(String text){
		return lex(text, 0);
	}
	
	private static List<Tok> lex(String text, int from){
		var lexer = new BytecodeLexer();
		lexer.start(text, from, text.length(), 0);
		var out      = new ArrayList<Tok>();
		int expected = from;
		while(lexer.getTokenType() != null){
			assertEquals(lexer.getTokenStart(), expected, "tokens must be contiguous, after " + (out.isEmpty()? "start" : out.getLast()));
			assertTrue(lexer.getTokenEnd()>lexer.getTokenStart());
			out.add(new Tok(lexer.getTokenType(), text.substring(lexer.getTokenStart(), lexer.getTokenEnd())));
			expected = lexer.getTokenEnd();
			lexer.advance();
		}
		assertEquals(expected, text.length(), "all text covered");
		return out;
	}
	
	/**
	 * Tokens of a line, without whitespace
	 */
	private static List<Tok> line(String line){
		return lex(line).stream().filter(t -> t.type != TokenType.WHITE_SPACE).toList();
	}
	
	private static Tok t(IElementType type, String text){
		return new Tok(type, text);
	}
	
	private static String textify(String resource) throws IOException{
		try(InputStream in = BytecodeLexerTest.class.getResourceAsStream(resource)){
			assertNotNull(in, resource);
			var sw = new StringWriter();
			new ClassReader(in).accept(new TraceClassVisitor(new PrintWriter(sw)), 0);
			return sw.toString();
		}
	}
	
	private static final String[] CLASSES = {
		"/org/objectweb/asm/idea/docs/InsnExplainerTest$Fixture.class",
		"/org/objectweb/asm/idea/docs/InsnExplainerTest$Fixture$R.class",
		"/org/objectweb/asm/idea/docs/FrameExplainerTest$Fixture.class",
		"/org/objectweb/asm/idea/docs/InfoExplainerTest$Fixture.class",
		"/org/objectweb/asm/idea/sync/InsnMappingFixture.class",
		};
	
	@Test
	public void coversRealOutputAndRestartsAtAnyLine() throws IOException{
		for(String c : CLASSES){
			String text = textify(c);
			var    all  = lex(text);
			// Starting at a line start gives the same tokens as getting there from the top
			int offset = 0;
			for(int i = 0; i<all.size(); i++){
				boolean lineStart = offset == 0 || text.charAt(offset - 1) == '\n';
				if(lineStart){
					var from = lex(text, offset);
					assertEquals(from, all.subList(i, all.size()), c + " from offset " + offset);
				}
				offset += all.get(i).text.length();
			}
		}
	}
	
	@Test
	public void everyInstructionStartsWithAnOpcode() throws IOException{
		var opcodes = Set.of(Printer.OPCODES);
		for(String c : CLASSES){
			for(String l : textify(c).split("\n")){
				String first = l.strip().split(" ")[0];
				if(l.startsWith("    ") && !l.startsWith("     ") && opcodes.contains(first)){
					assertEquals(line(l).getFirst(), t(OPCODE, first), l);
				}
			}
		}
	}
	
	@Test
	public void methodCall(){
		assertEquals(line("    INVOKESPECIAL java/lang/Object.<init> ()V"), List.of(
			t(OPCODE, "INVOKESPECIAL"), t(PACKAGE, "java/lang/"), t(CLASS_NAME, "Object"), t(PUNCTUATION, "."), t(METHOD_CALL, "<init>"),
			t(PARENS, "("), t(PARENS, ")"), t(PRIMITIVE, "V")));
		assertEquals(line("    INVOKESTATIC Foo.bar (ILjava/util/List;)[J"), List.of(
			t(OPCODE, "INVOKESTATIC"), t(CLASS_NAME, "Foo"), t(PUNCTUATION, "."), t(METHOD_CALL, "bar"),
			t(PARENS, "("), t(PRIMITIVE, "I"), t(PACKAGE, "Ljava/util/"), t(CLASS_NAME, "List"), t(PACKAGE, ";"), t(PARENS, ")"),
			t(BRACKETS, "["), t(PRIMITIVE, "J")));
	}
	
	@Test
	public void fieldAccess(){
		assertEquals(line("    GETFIELD a/B$C.count : I"), List.of(
			t(OPCODE, "GETFIELD"), t(PACKAGE, "a/"), t(CLASS_NAME, "B$C"), t(PUNCTUATION, "."), t(FIELD, "count"), t(PUNCTUATION, ":"), t(PRIMITIVE, "I")));
	}
	
	@Test
	public void declarations(){
		assertEquals(line("  public static run(Ljava/util/List;)V throws java/io/IOException"), List.of(
			t(KEYWORD, "public"), t(KEYWORD, "static"), t(METHOD_DECL, "run"),
			t(PARENS, "("), t(PACKAGE, "Ljava/util/"), t(CLASS_NAME, "List"), t(PACKAGE, ";"), t(PARENS, ")"), t(PRIMITIVE, "V"),
			t(KEYWORD, "throws"), t(PACKAGE, "java/io/"), t(CLASS_NAME, "IOException")));
		assertEquals(line("  private final Ljava/lang/String; name"), List.of(
			t(KEYWORD, "private"), t(KEYWORD, "final"), t(PACKAGE, "Ljava/lang/"), t(CLASS_NAME, "String"), t(PACKAGE, ";"), t(FIELD, "name")));
		assertEquals(line("public class Foo extends java/lang/Object {"), List.of(
			t(KEYWORD, "public"), t(KEYWORD, "class"), t(CLASS_NAME, "Foo"), t(KEYWORD, "extends"), t(PACKAGE, "java/lang/"), t(CLASS_NAME, "Object"),
			t(BRACES, "{")));
	}
	
	@Test
	public void operands(){
		assertEquals(line("    LDC \"a \\\"b\\\"\""), List.of(t(OPCODE, "LDC"), t(STRING, "\"a \\\"b\\\"\"")));
		assertEquals(line("    LDC 2L"), List.of(t(OPCODE, "LDC"), t(NUMBER, "2L")));
		assertEquals(line("    LDC -1.5E10D"), List.of(t(OPCODE, "LDC"), t(NUMBER, "-1.5E10D")));
		assertEquals(line("    LDC Ljava/lang/String;.class"), List.of(
			t(OPCODE, "LDC"), t(PACKAGE, "Ljava/lang/"), t(CLASS_NAME, "String"), t(PACKAGE, ";"), t(PUNCTUATION, "."), t(KEYWORD, "class")));
		assertEquals(line("    IFLE L12"), List.of(t(OPCODE, "IFLE"), t(LABEL, "L12")));
		assertEquals(line("    NEW Foo"), List.of(t(OPCODE, "NEW"), t(CLASS_NAME, "Foo")));
		assertEquals(line("    ALOAD 1"), List.of(t(OPCODE, "ALOAD"), t(NUMBER, "1")));
		assertEquals(line("      default: L3"), List.of(t(KEYWORD, "default"), t(PUNCTUATION, ":"), t(LABEL, "L3")));
		assertEquals(line("    INVOKEDYNAMIC get()Ljava/util/function/Supplier; ["), List.of(
			t(OPCODE, "INVOKEDYNAMIC"), t(METHOD_CALL, "get"), t(PARENS, "("), t(PARENS, ")"),
			t(PACKAGE, "Ljava/util/function/"), t(CLASS_NAME, "Supplier"), t(PACKAGE, ";"), t(BRACKETS, "[")));
	}
	
	@Test
	public void notInstructions(){
		assertEquals(line("   L0"), List.of(t(LABEL, "L0")));
		assertEquals(line("    LINENUMBER 12 L0"), List.of(t(DIRECTIVE, "LINENUMBER"), t(NUMBER, "12"), t(LABEL, "L0")));
		assertEquals(line("    LOCALVARIABLE this LFoo; L0 L2 0"), List.of(
			t(DIRECTIVE, "LOCALVARIABLE"), t(LOCAL_VAR, "this"), t(PACKAGE, "L"), t(CLASS_NAME, "Foo"), t(PACKAGE, ";"),
			t(LABEL, "L0"), t(LABEL, "L2"), t(NUMBER, "0")));
		assertEquals(line("    MAXSTACK = 2"), List.of(t(DIRECTIVE, "MAXSTACK"), t(PUNCTUATION, "="), t(NUMBER, "2")));
		assertEquals(line("  // access flags 0x21"), List.of(t(COMMENT, "// access flags 0x21")));
		assertEquals(line("  @Ljava/lang/Deprecated;() // invisible"), List.of(
			t(ANNOTATION, "@Ljava/lang/Deprecated;"), t(PARENS, "("), t(PARENS, ")"), t(COMMENT, "// invisible")));
	}
	
	@Test
	public void frames(){
		assertEquals(line("   FRAME FULL [java/lang/reflect/Type I T [Ljava/lang/reflect/Type; J] []"), List.of(
			t(DIRECTIVE, "FRAME"), t(DIRECTIVE, "FULL"), t(BRACKETS, "["),
			t(PACKAGE, "java/lang/reflect/"), t(CLASS_NAME, "Type"), t(PRIMITIVE, "I"), t(PRIMITIVE, "T"),
			t(BRACKETS, "["), t(PACKAGE, "Ljava/lang/reflect/"), t(CLASS_NAME, "Type"), t(PACKAGE, ";"), t(PRIMITIVE, "J"),
			t(BRACKETS, "]"), t(BRACKETS, "["), t(BRACKETS, "]")));
		assertEquals(line("   FRAME SAME1 java/lang/Throwable"), List.of(
			t(DIRECTIVE, "FRAME"), t(DIRECTIVE, "SAME1"), t(PACKAGE, "java/lang/"), t(CLASS_NAME, "Throwable")));
		assertEquals(line("   FRAME APPEND [I L3]"), List.of(
			t(DIRECTIVE, "FRAME"), t(DIRECTIVE, "APPEND"), t(BRACKETS, "["), t(PRIMITIVE, "I"), t(LABEL, "L3"), t(BRACKETS, "]")));
	}
}
