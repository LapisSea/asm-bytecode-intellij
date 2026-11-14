package org.objectweb.asm.idea.sync;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import java.util.regex.Pattern;

/**
 * Maps between source lines and lines of {@link org.objectweb.asm.util.Textifier} output, using the
 * {@code LINENUMBER} entries that ASM prints from the class file's LineNumberTable.
 * <p>
 * All line numbers handled by this class are 0-based (as used by IntelliJ documents), even though
 * {@code LINENUMBER} entries in the bytecode text are 1-based.
 */
public final class LineMapping{
	
	public static final LineMapping EMPTY = new LineMapping(new int[0], Map.of());
	
	/**
	 * An inclusive range of lines in the bytecode text.
	 */
	public record Range(int startLine, int endLine){ }
	
	private static final Pattern LABEL = Pattern.compile("L\\d+");
	
	private final int[]                     bytecodeToSource;
	private final Map<Integer, List<Range>> sourceToBytecode;
	
	private LineMapping(int[] bytecodeToSource, Map<Integer, List<Range>> sourceToBytecode){
		this.bytecodeToSource = bytecodeToSource;
		this.sourceToBytecode = sourceToBytecode;
	}
	
	/**
	 * Builds the mapping from the text produced by a {@link org.objectweb.asm.util.TraceClassVisitor} with the
	 * default Textifier. A LineNumberTable entry applies from its label until the next entry, so every code line
	 * inherits the most recent {@code LINENUMBER} of its method.
	 */
	public static LineMapping parse(String bytecodeText){
		return parse(bytecodeText, IntUnaryOperator.identity());
	}
	
	/**
	 * @param lineMap translates the class file's line numbers (0-based) to lines of the document shown as the source,
	 *                -1 for lines it does not have
	 */
	public static LineMapping parse(String bytecodeText, IntUnaryOperator lineMap){
		String[] lines   = bytecodeText.split("\n", -1);
		int[]    mapping = new int[lines.length];
		Arrays.fill(mapping, -1);
		
		int current = -1;
		for(int i = 0; i<lines.length; i++){
			String line = lines[i];
			// Method bodies are indented by at least 3 spaces (labels) or 4 (instructions).
			// Anything less indented is a class/field/method header or a blank line, which ends the method.
			if(!line.startsWith("   ")){
				current = -1;
				continue;
			}
			String trimmed = line.strip();
			
			if(trimmed.startsWith("LINENUMBER ")){
				int sourceLine = parseLineNumber(trimmed);
				if(sourceLine>=0) sourceLine = lineMap.applyAsInt(sourceLine);
				current = sourceLine;
				mapping[i] = sourceLine;
				// The label the line number is attached to belongs to it as well
				if(i>0 && mapping[i - 1] != sourceLine && isLabel(lines[i - 1])){
					mapping[i - 1] = sourceLine;
				}
				continue;
			}
			if(trimmed.startsWith("LOCALVARIABLE") || trimmed.startsWith("MAXSTACK") || trimmed.startsWith("MAXLOCALS")){
				// Trailing method metadata, not tied to any source line
				current = -1;
				continue;
			}
			if(isLabel(line) && i + 1<lines.length && lines[i + 1].strip().startsWith("LINENUMBER ")){
				// Handled when the LINENUMBER line itself is reached
				continue;
			}
			mapping[i] = current;
		}
		
		Map<Integer, List<Range>> reverse = new HashMap<>();
		int                       i       = 0;
		while(i<mapping.length){
			int src = mapping[i];
			if(src<0){
				i++;
				continue;
			}
			int start = i;
			while(i + 1<mapping.length && mapping[i + 1] == src) i++;
			reverse.computeIfAbsent(src, k -> new ArrayList<>()).add(new Range(start, i));
			i++;
		}
		
		return new LineMapping(mapping, reverse);
	}
	
	private static boolean isLabel(String line){
		return LABEL.matcher(line.strip()).matches();
	}
	
	private static int parseLineNumber(String trimmed){
		// Format: "LINENUMBER <line> <label>"
		String[] parts = trimmed.split("\\s+");
		try{
			return Integer.parseInt(parts[1]) - 1;
		}catch(RuntimeException e){
			return -1;
		}
	}
	
	/**
	 * @return the 0-based source line for a 0-based bytecode text line, or -1 if it does not map to source
	 */
	public int sourceLineFor(int bytecodeLine){
		if(bytecodeLine<0 || bytecodeLine>=bytecodeToSource.length) return -1;
		return bytecodeToSource[bytecodeLine];
	}
	
	/**
	 * @return the bytecode text ranges generated from a 0-based source line, in document order
	 */
	public List<Range> bytecodeRangesFor(int sourceLine){
		return sourceToBytecode.getOrDefault(sourceLine, List.of());
	}
	
	public boolean isEmpty(){
		return sourceToBytecode.isEmpty();
	}
}
