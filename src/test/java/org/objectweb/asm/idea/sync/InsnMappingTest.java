package org.objectweb.asm.idea.sync;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.JavaTestFixtureFactory;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.TraceClassVisitor;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.testng.Assert.*;

/**
 * End to end: real javac output of {@link InsnMappingFixture} against its source parsed by IntelliJ.
 */
public class InsnMappingTest{

	private CodeInsightTestFixture fixture;
	private String                 source;
	private String                 bytecode;
	private InsnMapping            mapping;

	@BeforeClass
	public void setUp() throws Exception{
		source = Files.readString(Path.of("src/test/java/org/objectweb/asm/idea/sync/InsnMappingFixture.java")).replace("\r", "");

		var printer = new RecordingTextifier();
		try(InputStream in = InsnMappingFixture.class.getResourceAsStream("InsnMappingFixture.class")){
			assertNotNull(in);
			new ClassReader(in).accept(new TraceClassVisitor(null, printer, null), 0);
		}
		var printed = printer.print();
		bytecode = printed.text();

		EdtTestUtil.runInEdtAndWait(() -> {
			var builder = IdeaTestFixtureFactory.getFixtureFactory().createLightFixtureBuilder("insnMapping");
			fixture = JavaTestFixtureFactory.getFixtureFactory().createCodeInsightFixture(builder.getFixture());
			fixture.setUp();
			var file = fixture.configureByText("InsnMappingFixture.java", source);
			var doc  = PsiDocumentManager.getInstance(fixture.getProject()).getDocument(file);
			mapping = ReadAction.compute(() -> InsnMapping.build(file, doc, "org/objectweb/asm/idea/sync/InsnMappingFixture", printed));
		});
		assertNotNull(mapping, "class not found in source");
	}

	@AfterClass(alwaysRun = true)
	public void tearDown() throws Exception{
		if(fixture != null) EdtTestUtil.runInEdtAndWait(() -> fixture.tearDown());
	}

	private int offsetOf(String text){
		int i = source.indexOf(text);
		assertTrue(i>=0, text);
		return i;
	}

	private int lineOf(int offset){
		return (int)source.substring(0, offset).chars().filter(c -> c == '\n').count();
	}

	/**
	 * @return bytecode text highlighted for the caret at the start of the given source text
	 */
	private String bytecodeAt(String sourceText){
		return bytecodeAt(offsetOf(sourceText), sourceText);
	}
	
	private String bytecodeAt(int offset, String what){
		String sourceText = what;
		var  ranges = mapping.bytecodeFor(offset, lineOf(offset));
		assertNotNull(ranges, sourceText);
		var lines = bytecode.split("\n", -1);
		return ranges.stream()
		             .map(r -> String.join("\n", List.of(lines).subList(r.startLine(), r.endLine() + 1)))
		             .collect(Collectors.joining("\n---\n"));
	}

	/**
	 * @return source text highlighted for the caret on the first bytecode line containing the given text
	 */
	private String sourceAt(String bytecodeText){
		var lines = bytecode.split("\n", -1);
		for(int i = 0; i<lines.length; i++){
			if(!lines[i].contains(bytecodeText)) continue;
			TextRange r = mapping.sourceFor(i);
			assertNotNull(r, bytecodeText);
			return r.substring(source);
		}
		fail("not in bytecode: " + bytecodeText);
		return null;
	}

	@Test
	public void callInAChain(){
		var text = bytecodeAt("filter(");
		assertTrue(text.contains("Stream.filter"), text);
		assertFalse(text.contains("Stream.map"), text);
		assertFalse(text.contains("Stream.toList"), text);
	}

	@Test
	public void methodReferences(){
		var first = bytecodeAt("Thing::foo");
		assertTrue(first.contains("INVOKEDYNAMIC"), first);
		assertTrue(first.contains("Thing.foo"), first);
		assertFalse(first.contains("Foo.stuff"), first);
		assertEquals(sourceAt("Stream.toList"), "toList()");
	}

	@Test
	public void lambdaBodyIsItsOwnMethod(){
		// The call in the lambda, not the declaration in Foo
		var text = bytecodeAt(offsetOf("f.something()") + 2, "f.something()");
		assertTrue(text.contains("Foo.something"), text);
		assertFalse(text.contains("Stream."), text);
	}

	@Test
	public void bytecodeToSource(){
		assertEquals(sourceAt("Stream.filter"), "filter(f -> f.something())");
		assertEquals(sourceAt("Stream.map"), "map(Thing::foo)");
		assertEquals(sourceAt("List.stream"), "stream()");
	}

	@Test
	public void localsFieldsAndOperators(){
		assertEquals(sourceAt("IMUL"), "*");
		assertEquals(sourceAt("IADD"), "+");
		assertEquals(sourceAt("ISTORE 3"), "c");
		var text = bytecodeAt("counter++");
		assertTrue(text.contains("PUTFIELD") && text.contains("counter"), text);
	}

	@Test
	public void fieldInitializerInConstructor(){
		var text = bytecodeAt("parseInt");
		assertTrue(text.contains("Integer.parseInt"), text);
		assertEquals(sourceAt("PUTFIELD org/objectweb/asm/idea/sync/InsnMappingFixture.counter"), "counter");
	}
}
