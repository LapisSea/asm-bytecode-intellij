package org.objectweb.asm.idea.docs;

import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.idea.sync.RecordingTextifier;
import org.objectweb.asm.idea.sync.RecordingTextifier.Insn;
import org.objectweb.asm.util.TraceClassVisitor;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.testng.Assert.*;

public class InsnExplainerTest{

	private static RecordingTextifier.Result record(Class<?> c, int flags) throws IOException{
		String name = c.getName();
		try(InputStream in = c.getResourceAsStream(name.substring(name.lastIndexOf('.') + 1) + ".class")){
			assertNotNull(in);
			var t = new RecordingTextifier();
			new ClassReader(in).accept(new TraceClassVisitor(null, t, null), flags);
			return t.print();
		}
	}

	private static Insn find(RecordingTextifier.Result r, String method, Predicate<Insn> p){
		return r.methods().stream().filter(m -> m.name.equals(method))
		        .flatMap(m -> m.insns.stream()).filter(p).findFirst().orElseThrow(() -> new AssertionError("no such instruction in " + method));
	}

	private static Insn find(Class<?> c, String method, int opcode) throws IOException{
		return find(record(c, 0), method, i -> i.opcode() == opcode);
	}

	/** Explanation without markup */
	private static String text(Insn insn){
		return text(insn, InsnExplainer.Resolver.NONE);
	}

	private static String text(Insn insn, InsnExplainer.Resolver resolver){
		String html = InsnExplainer.explain(insn, resolver);
		assertNotNull(html, insn.toString());
		return html.replaceAll("<[^>]+>", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replaceAll("\\s+", " ");
	}

	@Test
	public void constructorCall() throws IOException{
		var insn = find(Fixture.class, "make", Opcodes.INVOKESPECIAL);
		var text = text(insn);
		assertTrue(text.contains("Calling constructor of InsnExplainerTest.Fixture org.objectweb.asm.idea.docs with 2 arguments"), text);
		assertTrue(text.contains("String int"), text);
		assertFalse(text.contains("Returns"), text);
	}

	@Test
	public void constructorCallWithSourceInfo() throws IOException{
		var insn = find(Fixture.class, "make", Opcodes.INVOKESPECIAL);
		var text = text(insn, new InsnExplainer.Resolver(){
			@Override
			public InsnExplainer.MethodInfo method(String owner, String name, String descriptor){
				return new InsnExplainer.MethodInfo(List.of("name", "count"), null, null, List.of("IOException"));
			}

			@Override
			public @Nullable String fieldType(String owner, String name){ return null; }
		});
		assertTrue(text.contains("String name"), text);
		assertTrue(text.contains("int count"), text);
		assertTrue(text.contains("throws: IOException"), text);
	}

	@Test
	public void namedLocal() throws IOException{
		var text = text(find(record(Fixture.class, 0), "greet", i -> i.opcode() == Opcodes.ALOAD && i.name() != null && i.name().equals("who")));
		assertEquals(text.strip(), "Loads local variable \"who\" ( String ) from slot 1 (1 slot) onto the stack.");
	}

	@Test
	public void localsWithoutDebugInfo() throws IOException{
		var r = record(Fixture.class, ClassReader.SKIP_DEBUG);
		var loads = r.methods().stream().filter(m -> m.name.equals("greet")).flatMap(m -> m.insns.stream())
		             .filter(i -> i.opcode() == Opcodes.ALOAD).map(InsnExplainerTest::text).toList();
		assertTrue(loads.stream().anyMatch(t -> t.contains("Loads parameter 1 ( String ) from slot 1")), loads.toString());
		assertTrue(loads.stream().anyMatch(t -> t.contains("Loads this ( InsnExplainerTest.Fixture org.objectweb.asm.idea.docs ) from slot 0")), loads.toString());
		assertTrue(loads.stream().noneMatch(t -> t.contains("\"")), "no made up names: " + loads);
	}

	@Test
	public void wideLocal() throws IOException{
		var text = text(find(Fixture.class, "wide", Opcodes.LSTORE));
		assertTrue(text.contains("local variable \"b\" ( long ) at slot 2 (2 slots)"), text);
	}

	@Test
	public void stringConcat() throws IOException{
		var text = text(find(Fixture.class, "greet", Opcodes.INVOKEDYNAMIC));
		assertTrue(text.contains("Concatenates 2 values"), text);
		assertTrue(text.contains("\"Hello \" + ‹String› + \"!\" + ‹int›"), text);
	}

	@Test
	public void lambda() throws IOException{
		var text = text(find(Fixture.class, "lambda", Opcodes.INVOKEDYNAMIC));
		assertTrue(text.contains("Creates a lambda implementing Supplier java.util.function , whose method is get () \u2192 String"), text);
		assertTrue(text.contains("synthetic method lambda$lambda$0"), text);
		assertTrue(text.contains("captures 2 values"), text);
		assertTrue(text.contains("InsnExplainerTest.Fixture org.objectweb.asm.idea.docs this"), text);
		assertTrue(text.contains("String suffix"), text);
	}

	@Test
	public void methodReference() throws IOException{
		var text = text(find(Fixture.class, "ref", Opcodes.INVOKEDYNAMIC));
		assertTrue(text.contains("Creates a method reference implementing Predicate"), text);
		assertTrue(text.contains("calls method isEmpty"), text);
		assertTrue(text.contains("Captures nothing"), text);
	}

	@Test
	public void recordMethods() throws IOException{
		var text = text(find(Fixture.R.class, "toString", Opcodes.INVOKEDYNAMIC));
		assertTrue(text.contains("generated toString of record InsnExplainerTest.Fixture.R"), text);
		assertTrue(text.contains("components: a, b"), text);
	}

	@Test
	public void switches() throws IOException{
		var r    = record(Fixture.class, 0);
		var insn = find(r, "sw", i -> i.opcode() == Opcodes.LOOKUPSWITCH || i.opcode() == Opcodes.TABLESWITCH);
		var text = text(insn);
		assertTrue(text.matches(".*1 → L\\d+ .*5 → L\\d+ .*anything else → L\\d+.*"), text);
	}

	@Test
	public void jumpsAndFields() throws IOException{
		var jump = text(find(Fixture.class, "positive", Opcodes.IFLE)).strip();
		assertTrue(jump.matches("Continues at L\\d+ if the popped int is less than or equal to 0, otherwise with the next instruction\\."), jump);
		var get = text(find(Fixture.class, "positive", Opcodes.GETFIELD));
		assertTrue(get.contains("Pops an object reference ( InsnExplainerTest.Fixture org.objectweb.asm.idea.docs ) and pushes the value of its field count ( int )"), get);
	}

	@Test
	public void noExplanationForPlainInstructions() throws IOException{
		assertNull(InsnExplainer.explain(find(Fixture.class, "wide", Opcodes.LMUL), InsnExplainer.Resolver.NONE));
	}

	@SuppressWarnings("unused")
	static class Fixture{
		private final String name;
		private       int    count;

		Fixture(String name, int count) throws IOException{
			this.name = name;
			this.count = count;
		}

		static Fixture make() throws IOException{
			return new Fixture("x", 3);
		}

		String greet(String who){
			return "Hello " + who + "!" + count;
		}

		Supplier<String> lambda(String suffix){
			return () -> name + suffix;
		}

		static Predicate<String> ref(){
			return String::isEmpty;
		}

		static int sw(int i){
			switch(i){
				case 1: return 10;
				case 5: return 50;
				default: return 0;
			}
		}

		boolean positive(){
			return count>0;
		}

		static long wide(long a){
			long b = a*2;
			return b;
		}

		record R(int a, String b){ }
	}
}
