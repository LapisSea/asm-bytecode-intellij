package org.objectweb.asm.idea.nav;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.JavaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.LightTempDirTestFixtureImpl;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.idea.highlight.BytecodeReference;
import org.objectweb.asm.idea.sync.RecordingTextifier;
import org.objectweb.asm.util.TraceClassVisitor;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class NavigationTest{
	
	private CodeInsightTestFixture fixture;
	
	@BeforeClass
	public void setUp() throws Exception{
		EdtTestUtil.runInEdtAndWait(() -> {
			var builder = IdeaTestFixtureFactory.getFixtureFactory().createLightFixtureBuilder(new DefaultLightProjectDescriptor(), "navigation");
			fixture = JavaTestFixtureFactory.getFixtureFactory().createCodeInsightFixture(builder.getFixture(), new LightTempDirTestFixtureImpl(true));
			fixture.setUp();
			fixture.configureByText("Outer.java", """
				package p;
				public class Outer{
					int count;
					public Outer(){ }
					public Outer(Outer o, int i){ }
					void run(int i){ }
					void run(Outer o){ }
					void run(Outer o, int i){ }
					public static class Inner{ }
				}
				""");
		});
	}
	
	@AfterClass(alwaysRun = true)
	public void tearDown() throws Exception{
		if(fixture != null) EdtTestUtil.runInEdtAndWait(() -> fixture.tearDown());
	}
	
	/**
	 * What the reference resolves to, described inside the read action
	 */
	private String resolve(BytecodeReference.Kind kind, String owner, String name, String descriptor){
		var ref = new BytecodeReference(kind, owner, name, descriptor, new TextRange(0, 0), false);
		return EdtTestUtil.runInEdtAndGet(() -> ReadAction.compute(() -> switch(DeclarationResolver.resolve(fixture.getProject(), ref)){
			case null -> "nothing";
			case PsiClass c -> "class " + c.getQualifiedName();
			case PsiMethod m -> (m.isConstructor()? "constructor " : "method ") + m.getName() + "(" +
			                    Arrays.stream(m.getParameterList().getParameters()).map(p -> p.getType().getCanonicalText()).collect(Collectors.joining(", ")) + ")";
			case PsiField f -> "field " + f.getName();
			default -> "other";
		}));
	}
	
	@Test
	public void classes(){
		assertEquals(resolve(BytecodeReference.Kind.CLASS, "p/Outer", null, null), "class p.Outer");
		assertEquals(resolve(BytecodeReference.Kind.CLASS, "p/Outer$Inner", null, null), "class p.Outer.Inner");
		assertEquals(resolve(BytecodeReference.Kind.CLASS, "p/Missing", null, null), "nothing");
	}
	
	@Test
	public void overloadsByDescriptor(){
		// Not the first overload, so only the descriptor can find them
		assertEquals(resolve(BytecodeReference.Kind.METHOD, "p/Outer", "run", "(Lp/Outer;)V"), "method run(p.Outer)");
		assertEquals(resolve(BytecodeReference.Kind.METHOD, "p/Outer", "run", "(Lp/Outer;I)V"), "method run(p.Outer, int)");
		assertEquals(resolve(BytecodeReference.Kind.METHOD, "p/Outer", "<init>", "(Lp/Outer;I)V"), "constructor Outer(p.Outer, int)");
	}
	
	@Test
	public void fieldsAndFallbacks(){
		assertEquals(resolve(BytecodeReference.Kind.FIELD, "p/Outer", "count", null), "field count");
		// Unknown member: the class is the next best thing
		assertEquals(resolve(BytecodeReference.Kind.METHOD, "p/Outer", "gone", "()V"), "class p.Outer");
		assertEquals(resolve(BytecodeReference.Kind.METHOD, "p/Outer", "<clinit>", "()V"), "class p.Outer");
	}
	
	@Test
	public void sourceElementsBecomeBytecodeSymbols(){
		var symbols = EdtTestUtil.runInEdtAndGet(() -> ReadAction.compute(() -> {
			var outer = JavaPsiFacade.getInstance(fixture.getProject()).findClass("p.Outer", GlobalSearchScope.allScope(fixture.getProject()));
			var inner = outer.findInnerClassByName("Inner", false);
			return List.of(
				String.valueOf(FindUsagesInBytecodeAction.symbol(outer.findMethodsByName("run", false)[1])),
				String.valueOf(FindUsagesInBytecodeAction.symbol(outer.getConstructors()[1])),
				String.valueOf(FindUsagesInBytecodeAction.symbol(outer.findFieldByName("count", false))),
				String.valueOf(FindUsagesInBytecodeAction.symbol(inner))
			);
		}));
		assertEquals(symbols, List.of(
			"MemberSymbol[kind=METHOD, owner=p/Outer, name=run, descriptor=(Lp/Outer;)V]",
			"MemberSymbol[kind=METHOD, owner=p/Outer, name=<init>, descriptor=(Lp/Outer;I)V]",
			"MemberSymbol[kind=FIELD, owner=p/Outer, name=count, descriptor=null]",
			"ClassSymbol[internalName=p/Outer$Inner]"
		));
	}
	
	@Test
	public void foldsMethodsAndBootstrapArguments() throws Exception{
		RecordingTextifier.Result printed;
		try(InputStream in = getClass().getResourceAsStream("/org/objectweb/asm/idea/docs/InsnExplainerTest$Fixture.class")){
			var t = new RecordingTextifier();
			new ClassReader(in).accept(new TraceClassVisitor(null, t, null), 0);
			printed = t.print();
		}
		var folds = EdtTestUtil.runInEdtAndGet(() -> {
			var    doc     = EditorFactory.getInstance().createDocument(printed.text());
			Editor editor  = EditorFactory.getInstance().createViewer(doc, fixture.getProject());
			var    support = new BytecodeEditorSupport(fixture.getProject(), editor, n -> null);
			try{
				support.updateFolding(printed);
				return Arrays.stream(editor.getFoldingModel().getAllFoldRegions())
				             .map(r -> r.getPlaceholderText() + "|" + doc.getText(r.getTextRange())).toList();
			}finally{
				Disposer.dispose(support);
				EditorFactory.getInstance().releaseEditor(editor);
			}
		});
		long methods = printed.methods().size();
		assertEquals(folds.stream().filter(f -> f.startsWith(" ...|") && f.contains("MAXLOCALS")).count(), methods, folds.toString());
		var indy = folds.stream().filter(f -> f.startsWith("[...]|")).toList();
		assertEquals(indy.size(), 4, folds.toString());
		for(var f : indy){
			assertTrue(f.startsWith("[...]|[\n"), f);
			assertTrue(f.endsWith("]"), f);
		}
	}
}
