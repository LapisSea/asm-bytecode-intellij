package org.objectweb.asm.idea.nav;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.idea.highlight.BytecodeReference;
import org.objectweb.asm.idea.nav.BytecodeUsages.*;
import org.objectweb.asm.idea.sync.RecordingTextifier;
import org.objectweb.asm.util.TraceClassVisitor;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.testng.Assert.*;

public class BytecodeUsagesTest{

	private static RecordingTextifier.Result record(String nested) throws IOException{
		try(InputStream in = BytecodeUsagesTest.class.getResourceAsStream("BytecodeUsagesTest$" + nested + ".class")){
			assertNotNull(in, nested);
			var t = new RecordingTextifier();
			new ClassReader(in).accept(new TraceClassVisitor(null, t, null), 0);
			return t.print();
		}
	}

	private static final String OWNER = "org/objectweb/asm/idea/nav/BytecodeUsagesTest$Fixture";

	/** The usages as "LINE_TEXT_STRIPPED [w][d]" */
	private static List<String> describe(String text, List<Usage> usages){
		var lines = text.split("\n", -1);
		return usages.stream().map(u -> {
			assertEquals(text.substring(u.range().getStartOffset(), u.range().getEndOffset()).indexOf('\n'), -1);
			return lines[u.line()].strip() + (u.write()? " [w]" : "") + (u.declaration()? " [d]" : "");
		}).toList();
	}

	@Test
	public void fieldReadsAndWrites() throws IOException{
		var text   = record("Fixture").text();
		var usages = BytecodeUsages.find(text, new MemberSymbol(BytecodeReference.Kind.FIELD, OWNER, "count", null), String::equals);
		var d      = describe(text, usages);
		assertEquals(d.stream().filter(s -> s.endsWith("[d]")).count(), 1, d.toString());
		assertEquals(d.stream().filter(s -> s.startsWith("PUTFIELD") && s.endsWith("[w]")).count(), 2, d.toString());
		assertEquals(d.stream().filter(s -> s.startsWith("GETFIELD")).count(), 2, d.toString());
		for(var u : usages) assertEquals(text.substring(u.range().getStartOffset(), u.range().getEndOffset()), "count");
	}

	@Test
	public void methodsByDescriptor() throws IOException{
		var text = record("Fixture").text();
		var one  = describe(text, BytecodeUsages.find(text, new MemberSymbol(BytecodeReference.Kind.METHOD, OWNER, "add", "(I)V"), String::equals));
		assertEquals(one.size(), 3, one.toString());
		assertTrue(one.getFirst().endsWith("[d]"), one.toString());
		var two = describe(text, BytecodeUsages.find(text, new MemberSymbol(BytecodeReference.Kind.METHOD, OWNER, "add", "(II)V"), String::equals));
		assertEquals(two.size(), 1, "only the declaration: " + two);
	}

	@Test
	public void throughSubclass() throws IOException{
		var text = record("User").text();
		var sub  = "org/objectweb/asm/idea/nav/BytecodeUsagesTest$Sub";
		var sym  = new MemberSymbol(BytecodeReference.Kind.METHOD, OWNER, "add", "(I)V");
		assertEquals(BytecodeUsages.find(text, sym, String::equals).size(), 0, "javac names the subclass as the owner");
		var found = BytecodeUsages.find(text, sym, (used, declared) -> used.equals(sub) && declared.equals(OWNER));
		assertEquals(describe(text, found), List.of("INVOKEVIRTUAL " + sub + ".add (I)V"));
	}

	@Test
	public void innerClassConstructor() throws IOException{
		var text = record("Fixture").text();
		// The source says (I)V, javac adds the outer instance in front
		var sym = new MemberSymbol(BytecodeReference.Kind.METHOD, "org/objectweb/asm/idea/nav/BytecodeUsagesTest$Fixture$Inner", "<init>", "(I)V");
		var d   = describe(text, BytecodeUsages.find(text, sym, String::equals));
		assertEquals(d.size(), 1, d.toString());
		assertTrue(d.getFirst().startsWith("INVOKESPECIAL"), d.toString());
	}

	@Test
	public void localVariables() throws IOException{
		var r    = record("Fixture");
		var text = r.text();
		// n of loop(int n), not the n of add(int n) which is in the same slot
		int inc = text.indexOf("IINC 1");
		var sym = BytecodeUsages.at(text, r, inc + 2);
		assertTrue(sym instanceof LocalSymbol l && l.local().name().equals("n"), String.valueOf(sym));
		var d = describe(text, BytecodeUsages.find(text, sym, String::equals));
		assertEquals(d.stream().filter(s -> s.startsWith("LOCALVARIABLE n") && s.endsWith("[d]")).count(), 1, d.toString());
		assertEquals(d.stream().filter(s -> s.startsWith("ILOAD 1")).count(), 2, d.toString());
		assertTrue(d.stream().anyMatch(s -> s.startsWith("IINC 1") && s.endsWith("[w]")), d.toString());
	}

	@Test
	public void classes() throws IOException{
		var text = record("Fixture").text();
		var d    = describe(text, BytecodeUsages.find(text, new ClassSymbol(OWNER), String::equals));
		assertTrue(d.size()>5, d.toString());
		var sym = BytecodeUsages.at(text, null, text.indexOf("class " + OWNER) + 8);
		assertEquals(sym, new ClassSymbol(OWNER));
	}

	@Test
	public void labels() throws IOException{
		var text = record("Fixture").text();
		int jump = text.indexOf("GOTO L");
		var sym  = BytecodeUsages.at(text, null, jump + 6);
		assertTrue(sym instanceof LabelSymbol, String.valueOf(sym));
		var d = describe(text, BytecodeUsages.find(text, sym, String::equals));
		assertEquals(d.stream().filter(s -> s.endsWith("[d]")).count(), 1, d.toString());
		assertTrue(d.size()>=2, d.toString());
	}

	@SuppressWarnings("unused")
	static class Fixture{
		int count;

		void add(int n){
			count += n;
		}

		void add(int a, int b){ }

		int loop(int n){
			while(n<10) n++;
			add(n);
			add(1);
			count = 0;
			return count;
		}

		Object inner(){
			return new Inner(1);
		}

		class Inner{
			Inner(int x){ }
		}
	}

	static class Sub extends Fixture{ }

	@SuppressWarnings("unused")
	static class User{
		void use(Sub s){
			s.add(1);
		}
	}
}
