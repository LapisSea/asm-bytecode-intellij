package org.objectweb.asm.idea.docs;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.Printer;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.*;

public class JvmSpecTest{

	private static final String BASE = "https://example.org/jvms/";

	private static String sample() throws IOException{
		try(InputStream in = JvmSpecTest.class.getResourceAsStream("jvms-sample.html")){
			assertNotNull(in);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	public void splitsInstructions() throws IOException{
		var spec  = JvmSpec.parse(sample(), BASE);
		var aload = spec.get(Opcodes.ALOAD);
		assertNotNull(aload);
		assertEquals(aload.title(), "aload");
		assertEquals(aload.url(), BASE + "jvms-6.html#jvms-6.5.aload");
		assertEquals(List.copyOf(aload.sections().keySet()), List.of("Operation", "Forms", "Description"));
		assertEquals(JvmSpec.text(aload.sections().get("Operation")).strip(), "Load a thing");
	}

	@Test
	public void familiesAreFoundByEachOpcode() throws IOException{
		var spec = JvmSpec.parse(sample(), BASE);
		assertSame(spec.get(Opcodes.IFEQ), spec.get(Opcodes.IFNE));
		assertEquals(spec.get(Opcodes.IFEQ).title(), "if<cond>");
		assertEquals(spec.size(), 3);
	}

	@Test
	public void linksAreAbsolute() throws IOException{
		var desc = JvmSpec.parse(sample(), BASE).get(Opcodes.ALOAD).sections().get("Description");
		assertTrue(desc.contains("href=\"" + BASE + "jvms-2.html#jvms-2.6\""), desc);
		assertTrue(desc.contains("href=\"" + BASE + "jvms-6.html#jvms-6.5.wide\""), desc);
		assertFalse(desc.contains("titlepage"), desc);
	}

	@Test
	public void garbageIsEmpty(){
		assertTrue(JvmSpec.parse("<html>not the spec</html>", BASE).isEmpty());
	}

	/**
	 * Against the real page, when given one: JVMS_HTML=path/to/jvms-6.html
	 */
	@Test
	public void realSpecCoversEveryOpcode() throws IOException{
		String path = System.getenv("JVMS_HTML");
		if(path == null) throw new SkipException("JVMS_HTML not set");
		var spec    = JvmSpec.parse(Files.readString(Path.of(path)), BASE);
		var missing = new ArrayList<String>();
		for(int op = 0; op<Printer.OPCODES.length; op++){
			String name = Printer.OPCODES[op];
			if(name.isEmpty()) continue;
			var e = spec.get(op);
			if(e == null || !e.sections().containsKey("Description") || !e.sections().containsKey("Operation")) missing.add(name);
		}
		assertEquals(missing, List.of());
	}
}
