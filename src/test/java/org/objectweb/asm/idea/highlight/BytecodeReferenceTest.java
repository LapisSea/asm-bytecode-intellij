package org.objectweb.asm.idea.highlight;

import org.testng.annotations.Test;

import static org.objectweb.asm.idea.highlight.BytecodeReference.Kind.CLASS;
import static org.objectweb.asm.idea.highlight.BytecodeReference.Kind.FIELD;
import static org.objectweb.asm.idea.highlight.BytecodeReference.Kind.LABEL;
import static org.objectweb.asm.idea.highlight.BytecodeReference.Kind.METHOD;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class BytecodeReferenceTest{
	
	private static final String TEXT = """
		// class version 69.0 (69)
		// access flags 0x21
		public class com/example/Greeter extends java/lang/Object {
		
		  // access flags 0x2
		  private I count
		
		  // access flags 0x1
		  public greet(Ljava/lang/String;I)Ljava/lang/String;
		   L0
		    LINENUMBER 12 L0
		    ALOAD 0
		    GETFIELD com/example/Greeter.count : I
		    IFLE L1
		    INVOKESTATIC java/util/Objects.requireNonNull (Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;
		    INVOKEDYNAMIC get()Ljava/util/function/Supplier; [
		      java/lang/invoke/LambdaMetafactory.metafactory(Ljava/lang/invoke/MethodHandles$Lookup;)Ljava/lang/invoke/CallSite;
		    ]
		    GOTO L0
		   L1
		    NEW Foo
		    ARETURN
		    MAXSTACK = 2
		    MAXLOCALS = 2
		
		  // access flags 0x1
		  public other()V
		   L0
		    GOTO L1
		   L1
		    RETURN
		}
		""";
	
	private static int at(String marker, int delta){
		int i = TEXT.indexOf(marker);
		assertTrue(i>=0, marker);
		return i + delta;
	}
	
	private static BytecodeReference ref(String marker, int delta){
		return BytecodeReference.at(TEXT, at(marker, delta), "com/example/Greeter");
	}
	
	@Test
	public void declaredClass(){
		assertEquals(BytecodeReference.declaredClass(TEXT), "com/example/Greeter");
	}
	
	@Test
	public void classes(){
		// On the package part or the name, the whole name is the link
		var r = ref("java/lang/Object {", 2);
		assertEquals(r.kind(), CLASS);
		assertEquals(r.owner(), "java/lang/Object");
		assertEquals(r.range().substring(TEXT), "java/lang/Object");
		assertEquals(ref("java/lang/Object {", 12).owner(), "java/lang/Object");
		assertEquals(ref("NEW Foo", 5).owner(), "Foo");
	}
	
	@Test
	public void descriptors(){
		// (Ljava/lang/Object;Ljava/lang/String;): each descriptor on its own, without L and ;
		var obj = ref("(Ljava/lang/Object;Ljava/lang/String;)", 14);
		assertEquals(obj.owner(), "java/lang/Object");
		assertEquals(obj.range().substring(TEXT), "java/lang/Object");
		var str = ref("(Ljava/lang/Object;Ljava/lang/String;)", 30);
		assertEquals(str.owner(), "java/lang/String");
	}
	
	@Test
	public void members(){
		var m = ref("requireNonNull", 3);
		assertEquals(m.kind(), METHOD);
		assertEquals(m.owner(), "java/util/Objects");
		assertEquals(m.name(), "requireNonNull");
		assertEquals(m.descriptor(), "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;");
		assertEquals(m.range().substring(TEXT), "requireNonNull");
		
		var bsm = ref("metafactory", 0);
		assertEquals(bsm.owner(), "java/lang/invoke/LambdaMetafactory");
		assertEquals(bsm.descriptor(), "(Ljava/lang/invoke/MethodHandles$Lookup;)Ljava/lang/invoke/CallSite;");
		
		var f = ref("Greeter.count", 9);
		assertEquals(f.kind(), FIELD);
		assertEquals(f.owner(), "com/example/Greeter");
		assertEquals(f.name(), "count");
		// Right after the word still counts
		assertEquals(ref("Greeter.count", 13).name(), "count");
	}
	
	@Test
	public void declarationsBelongToTheClass(){
		var m = ref("greet(", 1);
		assertEquals(m.kind(), METHOD);
		assertEquals(m.owner(), "com/example/Greeter");
		assertEquals(m.descriptor(), "(Ljava/lang/String;I)Ljava/lang/String;");
		var f = ref("I count", 3);
		assertEquals(f.kind(), FIELD);
		assertEquals(f.name(), "count");
		assertNull(BytecodeReference.at(TEXT, at("greet(", 1), null), "unknown class");
	}
	
	@Test
	public void nothingToNavigate(){
		assertNull(ref("INVOKEDYNAMIC get", 15), "an invokedynamic name is not a method of any class");
		assertNull(ref("ALOAD 0", 1));
		assertNull(ref("// access flags 0x21", 5));
	}
	
	@Test
	public void labels(){
		var r = ref("IFLE L1", 5);
		assertEquals(r.kind(), LABEL);
		assertEquals(r.name(), "L1");
		var def = BytecodeReference.labelDefinition(TEXT, at("IFLE L1", 5), "L1");
		assertEquals(def.getStartOffset(), at("   L1\n    NEW", 3));
		// Label names restart in every method
		var other = BytecodeReference.labelDefinition(TEXT, at("GOTO L1", 5), "L1");
		assertEquals(other.getStartOffset(), at("   L1\n    RETURN", 3));
		assertEquals(BytecodeReference.labelOccurrences(TEXT, at("IFLE L1", 5), "L0").size(), 3);
	}
}
