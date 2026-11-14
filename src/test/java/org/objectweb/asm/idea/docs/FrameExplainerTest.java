package org.objectweb.asm.idea.docs;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.idea.sync.RecordingTextifier;
import org.objectweb.asm.idea.sync.RecordingTextifier.Frame;
import org.objectweb.asm.util.TraceClassVisitor;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.testng.Assert.*;

public class FrameExplainerTest{

	private static RecordingTextifier.Result record(int flags) throws IOException{
		try(InputStream in = FrameExplainerTest.class.getResourceAsStream("FrameExplainerTest$Fixture.class")){
			assertNotNull(in);
			var t = new RecordingTextifier();
			new ClassReader(in).accept(new TraceClassVisitor(null, t, null), flags);
			return t.print();
		}
	}

	private static List<Frame> frames(RecordingTextifier.Result r, String method){
		return r.methods().stream().filter(m -> m.name.equals(method)).findFirst().orElseThrow().frames;
	}

	private static String text(Frame f){
		return InsnExplainer.explainFrame(f).replaceAll("<[^>]+>", " ").replace("&lt;", "<").replace("&gt;", ">").replaceAll("\\s+", " ");
	}

	@Test
	public void framesPointAtTheirText() throws IOException{
		var r     = record(0);
		var lines = r.text().split("\n", -1);
		int count = 0;
		for(var m : r.methods()){
			for(var f : m.frames){
				count++;
				assertTrue(lines[f.textStart()].strip().startsWith("FRAME"), lines[f.textStart()]);
				assertEquals(f.textEnd(), f.textStart());
			}
		}
		assertTrue(count>3);
	}

	@Test
	public void compressedFramesAreExpanded() throws IOException{
		// Compressed frames, put together, must equal what ASM computes when asked to expand them
		var compressed = record(0);
		var expanded   = record(ClassReader.EXPAND_FRAMES);
		for(var m : compressed.methods()){
			var exp = frames(expanded, m.name);
			assertEquals(m.frames.size(), exp.size(), m.name);
			for(int i = 0; i<exp.size(); i++){
				assertEquals(exp.get(i).kind(), Opcodes.F_NEW);
				assertEquals(m.frames.get(i).locals(), exp.get(i).locals(), m.name + " frame " + i);
				assertEquals(m.frames.get(i).stack(), exp.get(i).stack(), m.name + " frame " + i);
			}
		}
	}

	@Test
	public void appendAndChop() throws IOException{
		var fs   = frames(record(0), "loop");
		var kinds = fs.stream().map(Frame::kind).toList();
		assertTrue(kinds.contains(Opcodes.F_APPEND), kinds.toString());
		assertTrue(kinds.contains(Opcodes.F_CHOP), kinds.toString());
		var append = fs.stream().filter(f -> f.kind() == Opcodes.F_APPEND).findFirst().orElseThrow();
		var text   = text(append);
		assertTrue(text.contains("APPEND : the local variables of the previous frame, plus 2 new ones"), text);
		assertTrue(text.contains("slot 0: I int, or boolean, byte, char, short n"), text);
		assertTrue(text.contains("slot 1: I int, or boolean, byte, char, short sum"), text);
		assertTrue(text.contains("slot 2: I int, or boolean, byte, char, short i"), text);
		assertTrue(text.contains("Operand stack: empty."), text);
	}

	@Test
	public void wideLocalsTakeTwoSlots() throws IOException{
		var f    = frames(record(0), "wide").getFirst();
		var text = text(f);
		assertTrue(text.contains("slot 0–1: J long, 2 slots a"), text);
		assertTrue(text.contains("slot 2: I"), text);
	}

	@Test
	public void caughtException() throws IOException{
		var f = frames(record(0), "caught").stream().filter(x -> x.kind() == Opcodes.F_SAME1).findFirst().orElseThrow();
		var text = text(f);
		assertTrue(text.contains("typically the caught exception"), text);
		assertTrue(text.contains("Operand stack (bottom first): RuntimeException"), text);
	}

	@Test
	public void uninitializedObject() throws IOException{
		var f = frames(record(0), "uninitialized").stream().filter(x -> !x.stack().isEmpty()).findFirst().orElseThrow();
		var text = text(f);
		assertTrue(text.matches(".*a new StringBuilder created by NEW at L\\d+ , whose constructor has not run yet.*"), text);
	}

	@Test
	public void unusableSlots(){
		var method = record0Method();
		var f = new Frame(method, 0, Opcodes.F_FULL, List.of(), 0, List.of("java/lang/String", Opcodes.TOP, Opcodes.INTEGER),
		                  new String[]{"s", null, "x"}, List.of(), -1, new int[]{0, 0});
		var text = text(f);
		assertTrue(text.contains("FULL lists everything"), text);
		assertTrue(text.contains("slot 1: T unusable"), text);
		assertTrue(text.contains("T (top) marks a slot with nothing usable"), text);
	}

	private static RecordingTextifier.Method record0Method(){
		try{
			return record(0).methods().getFirst();
		}catch(IOException e){
			throw new RuntimeException(e);
		}
	}

	@SuppressWarnings("unused")
	static class Fixture{
		static int loop(int n){
			int sum = 0;
			for(int i = 0; i<n; i++){
				sum += i;
			}
			return sum;
		}

		static long wide(long a, int b){
			if(b>0) a++;
			return a;
		}

		static String caught(Runnable r){
			try{
				r.run();
			}catch(RuntimeException e){
				return e.getMessage();
			}
			return null;
		}

		static StringBuilder uninitialized(boolean b){
			return new StringBuilder(b? "a" : "b");
		}
	}
}
