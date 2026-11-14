package org.objectweb.asm.idea.sync;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.Printer;
import org.objectweb.asm.util.TraceClassVisitor;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.testng.Assert.*;

public class RecordingTextifierTest{

	private static ClassReader fixture() throws IOException{
		try(InputStream in = Fixture.class.getResourceAsStream("RecordingTextifierTest$Fixture.class")){
			assertNotNull(in);
			return new ClassReader(in);
		}
	}

	private static RecordingTextifier.Result record(int flags) throws IOException{
		var t = new RecordingTextifier();
		fixture().accept(new TraceClassVisitor(null, t, null), flags);
		return t.print();
	}

	private static RecordingTextifier.Method method(RecordingTextifier.Result r, String name){
		return r.methods().stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
	}

	@Test
	void decompiledLines() throws IOException{
		var plain  = new RecordingTextifier();
		var mapped = new RecordingTextifier(l -> l + 100);
		fixture().accept(new org.objectweb.asm.util.TraceClassVisitor(null, plain, null), 0);
		fixture().accept(new org.objectweb.asm.util.TraceClassVisitor(null, mapped, null), 0);
		var a = method(plain.print(), "sum").insns;
		var b = method(mapped.print(), "sum").insns;
		assertEquals(a.size(), b.size());
		for(int i = 0; i<a.size(); i++) assertEquals(b.get(i).line(), a.get(i).line() + 100);
	}

	@Test
	public void sameTextAsPlainTextifier() throws IOException{
		for(int flags : new int[]{0, ClassReader.SKIP_DEBUG, ClassReader.SKIP_FRAMES, ClassReader.EXPAND_FRAMES}){
			var sw = new StringWriter();
			fixture().accept(new TraceClassVisitor(new PrintWriter(sw)), flags);
			assertEquals(record(flags).text(), sw.toString(), "flags " + flags);
		}
	}

	@Test
	public void instructionsPointAtTheirText() throws IOException{
		var r     = record(0);
		var lines = r.text().split("\n", -1);
		int count = 0;
		for(var m : r.methods()){
			for(var insn : m.insns){
				count++;
				assertTrue(insn.textStart()>=0 && insn.textEnd()>=insn.textStart(), insn.toString());
				String first = lines[insn.textStart()].strip();
				assertTrue(first.startsWith(Printer.OPCODES[insn.opcode()]), insn + " -> " + first);
			}
		}
		assertTrue(count>20);
	}

	@Test
	public void multiLineInvokeDynamic() throws IOException{
		var indys = method(record(0), "run").insns.stream().filter(i -> i.opcode() == Opcodes.INVOKEDYNAMIC).toList();
		assertEquals(indys.size(), 3);
		for(var i : indys) assertTrue(i.textEnd()>i.textStart(), "bootstrap arguments are printed on extra lines");
		// Thing::foo, f->f.something(), Foo::stuff
		assertEquals(indys.get(0).name(), "foo");
		assertTrue(indys.get(1).isLambdaImpl(), indys.get(1).name());
		assertEquals(indys.get(2).name(), "stuff");
	}

	@Test
	public void localVariableNames() throws IOException{
		var vars = method(record(0), "sum").insns.stream()
		                                         .filter(i -> i.opcode()>=Opcodes.ILOAD && i.opcode()<=Opcodes.ALOAD || i.opcode()>=Opcodes.ISTORE && i.opcode()<=Opcodes.ASTORE)
		                                         .map(RecordingTextifier.Insn::name)
		                                         .toList();
		// int c = a + b; return c*2;
		assertEquals(vars, List.of("a", "b", "c", "c"));
	}

	@Test
	public void sourceLines() throws IOException{
		var insns = method(record(0), "sum").insns;
		int first = insns.getFirst().line();
		assertTrue(first>0);
		assertEquals(insns.getLast().line(), first + 1, "return is on the next line");
		for(var i : method(record(ClassReader.SKIP_DEBUG), "sum").insns){
			assertEquals(i.line(), -1);
			assertNull(i.name());
		}
	}

	@SuppressWarnings("unused")
	static class Fixture{
		record Foo(String stuff){
			boolean something(){ return !stuff.isEmpty(); }
		}

		record Thing(Foo foo){ }

		static int sum(int a, int b){
			int c = a + b;
			return c*2;
		}

		static List<String> run(List<Thing> things){
			return things.stream().map(Thing::foo).filter(f -> f.something()).map(Foo::stuff).toList();
		}
	}
}
