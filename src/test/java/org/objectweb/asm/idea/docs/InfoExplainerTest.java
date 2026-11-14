package org.objectweb.asm.idea.docs;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.idea.sync.RecordingTextifier;
import org.objectweb.asm.idea.sync.RecordingTextifier.Info;
import org.objectweb.asm.idea.sync.RecordingTextifier.LabelInfo;
import org.objectweb.asm.idea.sync.RecordingTextifier.LineInfo;
import org.objectweb.asm.idea.sync.RecordingTextifier.MaxsInfo;
import org.objectweb.asm.idea.sync.RecordingTextifier.TryCatchInfo;
import org.objectweb.asm.idea.sync.RecordingTextifier.VarInfo;
import org.objectweb.asm.util.TraceClassVisitor;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.regex.Pattern;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class InfoExplainerTest{
	
	private static RecordingTextifier.Result record() throws IOException{
		try(InputStream in = InfoExplainerTest.class.getResourceAsStream("InfoExplainerTest$Fixture.class")){
			assertNotNull(in);
			var t = new RecordingTextifier();
			new ClassReader(in).accept(new TraceClassVisitor(null, t, null), 0);
			return t.print();
		}
	}
	
	private static List<Info> infos(String method) throws IOException{
		return record().methods().stream().filter(m -> m.name.equals(method)).findFirst().orElseThrow().infos;
	}
	
	private static String text(Info info){
		return InsnExplainer.explainInfo(info).replaceAll("<[^>]+>", " ").replace("&lt;", "<").replace("&gt;", ">").replaceAll("\\s+", " ").strip();
	}
	
	private static final Pattern INFO_LINE = Pattern.compile("\\s*(L\\d+|LINENUMBER .*|LOCALVARIABLE .*|TRYCATCHBLOCK .*|MAXSTACK = \\d+|MAXLOCALS = \\d+)");
	
	@Test
	public void everyInfoLineIsRecorded() throws IOException{
		var r       = record();
		var lines   = r.text().split("\n", -1);
		var covered = new boolean[lines.length];
		for(var m : r.methods()){
			for(var i : m.infos){
				for(int l = i.textStart(); l<=i.textEnd(); l++) covered[l] = true;
			}
		}
		int count = 0;
		for(int l = 0; l<lines.length; l++){
			if(!INFO_LINE.matcher(lines[l]).matches()) continue;
			count++;
			assertTrue(covered[l], "line " + l + ": " + lines[l]);
		}
		assertTrue(count>10);
	}
	
	@Test
	public void lineNumber() throws IOException{
		var text = text(infos("caught").stream().filter(i -> i instanceof LineInfo).findFirst().orElseThrow());
		assertTrue(text.matches("Line \\d+ of the source file: the instructions from L\\d+ up to the next LINENUMBER were compiled from it\\..*"), text);
		assertTrue(text.contains("debug information , not something that executes"), text);
	}
	
	@Test
	public void localVariable() throws IOException{
		var v    = infos("generic").stream().filter(i -> i instanceof VarInfo vi && vi.local().name().equals("list")).findFirst().orElseThrow();
		var text = text(v);
		assertTrue(text.contains("Local variable list ( List<String> ) is in slot 0 from L"), text);
		assertTrue(text.contains("generic type"), text);
		assertTrue(text.contains("debug information"), text);
	}
	
	@Test
	public void tryCatch() throws IOException{
		var all   = infos("caught").stream().filter(i -> i instanceof TryCatchInfo).map(i -> (TryCatchInfo)i).toList();
		var typed = text(all.stream().filter(t -> t.type() != null).findFirst().orElseThrow());
		assertTrue(typed.contains("If an IllegalStateException (or a subclass) is thrown"), typed);
		assertFalse(typed.contains("debug information ,"), typed);
		var any = text(all.stream().filter(t -> t.type() == null).findFirst().orElseThrow());
		assertTrue(any.contains("finally"), any);
	}
	
	@Test
	public void labelUses() throws IOException{
		var r       = record();
		var caught  = r.methods().stream().filter(m -> m.name.equals("caught")).findFirst().orElseThrow();
		var handler = caught.infos.stream().filter(i -> i instanceof TryCatchInfo t && t.type() != null).map(i -> ((TryCatchInfo)i).handler()).findFirst().orElseThrow();
		var label   = caught.infos.stream().filter(i -> i instanceof LabelInfo l && l.name().equals(handler)).findFirst().orElseThrow();
		var text    = text(label);
		assertTrue(text.contains("It does not execute"), text);
		assertTrue(text.contains("Start of an exception handler ( IllegalStateException )"), text);
	}
	
	@Test
	public void maxs() throws IOException{
		var text = text(infos("generic").stream().filter(i -> i instanceof MaxsInfo).findFirst().orElseThrow());
		assertTrue(text.contains("MAXSTACK = "), text);
		assertTrue(text.contains("MAXLOCALS = 1 : the number of local variable slots"), text);
	}
	
	@SuppressWarnings("unused")
	static class Fixture{
		static int caught(Runnable r){
			try{
				r.run();
				return 1;
			}catch(IllegalStateException e){
				return 2;
			}finally{
				System.out.println();
			}
		}
		
		static int generic(List<String> list){
			return list.size();
		}
	}
}
