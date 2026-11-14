package org.objectweb.asm.idea.sync;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.TraceClassVisitor;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.testng.Assert.*;

public class LineMappingTest{
	
	private static final String SAMPLE = String.join("\n",
		/* 0*/ "// class version 65.0 (65)",
		/* 1*/ "// access flags 0x21",
		/* 2*/ "public class Foo {",
		/* 3*/ "",
		/* 4*/ "  // access flags 0x1",
		/* 5*/ "  public <init>()V",
		/* 6*/ "   L0",
		/* 7*/ "    LINENUMBER 3 L0",
		/* 8*/ "    ALOAD 0",
		/* 9*/ "    INVOKESPECIAL java/lang/Object.<init> ()V",
		/*10*/ "   L1",
		/*11*/ "    LINENUMBER 4 L1",
		/*12*/ "    RETURN",
		/*13*/ "   L2",
		/*14*/ "    LOCALVARIABLE this LFoo; L0 L2 0",
		/*15*/ "    MAXSTACK = 1",
		/*16*/ "    MAXLOCALS = 1",
		/*17*/ "",
		/*18*/ "  // access flags 0x1",
		/*19*/ "  public foo()I",
		/*20*/ "   L0",
		/*21*/ "    LINENUMBER 3 L0",
		/*22*/ "    ICONST_1",
		/*23*/ "    IRETURN",
		/*24*/ "    MAXSTACK = 1",
		/*25*/ "    MAXLOCALS = 1",
		/*26*/ "}"
	);
	
	@Test
	void bytecodeToSource(){
		var m = LineMapping.parse(SAMPLE);
		for(int line : new int[]{0, 1, 2, 3, 4, 5, 14, 15, 16, 17, 18, 19, 24, 25, 26}){
			assertEquals(m.sourceLineFor(line), -1, "line " + line);
		}
		for(int line = 6; line<=9; line++) assertEquals(m.sourceLineFor(line), 2, "line " + line);
		for(int line = 10; line<=13; line++) assertEquals(m.sourceLineFor(line), 3, "line " + line);
		for(int line = 20; line<=23; line++) assertEquals(m.sourceLineFor(line), 2, "line " + line);
		assertEquals(m.sourceLineFor(-1), -1);
		assertEquals(m.sourceLineFor(1000), -1);
	}
	
	@Test
	void sourceToBytecode(){
		var m = LineMapping.parse(SAMPLE);
		assertEquals(m.bytecodeRangesFor(2), List.of(new LineMapping.Range(6, 9), new LineMapping.Range(20, 23)));
		assertEquals(m.bytecodeRangesFor(3), List.of(new LineMapping.Range(10, 13)));
		assertEquals(m.bytecodeRangesFor(0), List.of());
	}
	
	@Test
	void decompiledLines(){
		// Decompiled code puts class file line 2 on line 10, line 3 nowhere
		var m = LineMapping.parse(SAMPLE, l -> l == 2? 10 : -1);
		assertEquals(m.bytecodeRangesFor(10), List.of(new LineMapping.Range(6, 9), new LineMapping.Range(20, 23)));
		assertEquals(m.bytecodeRangesFor(2), List.of());
		assertEquals(m.bytecodeRangesFor(3), List.of());
		for(int line = 10; line<=13; line++) assertEquals(m.sourceLineFor(line), -1, "line " + line);
	}
	
	@Test
	void noDebugInfo(){
		var m = LineMapping.parse(SAMPLE.replaceAll("(?m)^ +LINENUMBER.*\n", ""));
		assertTrue(m.isEmpty());
	}
	
	/**
	 * Checks the assumptions about the Textifier format against real ASM output.
	 */
	@Test
	void realTextifierOutput() throws IOException{
		String text;
		try(InputStream in = Fixture.class.getResourceAsStream("LineMappingTest$Fixture.class")){
			assertNotNull(in);
			var sw = new StringWriter();
			new ClassReader(in).accept(new TraceClassVisitor(new PrintWriter(sw)), 0);
			text = sw.toString();
		}
		var m     = LineMapping.parse(text);
		var lines = text.split("\n", -1);
		assertFalse(m.isEmpty());
		
		int lineNumbers = 0;
		for(int i = 0; i<lines.length; i++){
			String t = lines[i].strip();
			if(t.startsWith("LINENUMBER ")){
				lineNumbers++;
				int line     = i;
				int expected = Integer.parseInt(t.split(" ")[1]) - 1;
				assertEquals(m.sourceLineFor(i), expected, lines[i]);
				assertEquals(m.sourceLineFor(i - 1), expected, "label before " + lines[i]);
				assertTrue(m.bytecodeRangesFor(expected).stream().anyMatch(r -> r.startLine()<=line && line<=r.endLine()));
			}
			if(t.startsWith("LOCALVARIABLE") || t.startsWith("MAXSTACK") || t.startsWith("MAXLOCALS")){
				assertEquals(m.sourceLineFor(i), -1, lines[i]);
			}
			if(t.startsWith("IRETURN") || t.startsWith("INVOKE")){
				assertNotEquals(m.sourceLineFor(i), -1, lines[i]);
			}
		}
		assertTrue(lineNumbers>=4, text);
	}
	
	@SuppressWarnings("unused")
	static class Fixture{
		int field = Integer.parseInt("1");
		
		int sum(int a, int b){
			int c = a + b;
			if(c>10){
				return c*2;
			}
			return c;
		}
		
		Runnable lambda(){
			return () -> System.out.println(field);
		}
	}
}
