package org.objectweb.asm.idea.nav;

import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.idea.highlight.BytecodeReference;
import org.objectweb.asm.idea.sync.RecordingTextifier.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;

/**
 * Finds where something is used in one class's bytecode text.
 */
public final class BytecodeUsages{
	private BytecodeUsages(){ }

	/**
	 * What usages are searched for.
	 */
	public sealed interface Symbol{
		/** For titles: what the user would call it */
		String displayName();
	}

	public record ClassSymbol(String internalName) implements Symbol{
		@Override
		public String displayName(){ return simpleName(internalName); }
	}

	/** @param descriptor null for fields */
	public record MemberSymbol(BytecodeReference.Kind kind, String owner, String name, @Nullable String descriptor) implements Symbol{
		@Override
		public String displayName(){
			return simpleName(owner) + "." + (name.equals("<init>")? simpleName(owner) : name) + (kind == BytecodeReference.Kind.METHOD? "()" : "");
		}
	}

	/** @param offset any offset in the method, labels are per method */
	public record LabelSymbol(String name, int offset) implements Symbol{
		@Override
		public String displayName(){ return name; }
	}

	/** @param local null without debug info, then the slot is all there is */
	public record LocalSymbol(Method method, int slot, @Nullable LocalVariable local) implements Symbol{
		@Override
		public String displayName(){ return local != null? local.name() : "slot " + slot; }
	}

	/**
	 * @param range       the text that refers to the symbol
	 * @param write       whether the value is stored rather than read
	 * @param declaration where it is declared rather than used
	 */
	public record Usage(TextRange range, int line, boolean write, boolean declaration){ }

	private static String simpleName(String internalName){
		return internalName.substring(internalName.lastIndexOf('/') + 1).replace('$', '.');
	}

	// ------------------------------------------------------------ what is at the caret

	/**
	 * @param printed what the text was printed from, for local variables; null if not available
	 */
	public static @Nullable Symbol at(CharSequence text, @Nullable Result printed, int offset){
		var ref = BytecodeReference.at(text, offset, BytecodeReference.declaredClass(text));
		if(ref != null){
			return switch(ref.kind()){
				case CLASS -> new ClassSymbol(ref.owner());
				case METHOD, FIELD -> new MemberSymbol(ref.kind(), ref.owner(), ref.name(), ref.descriptor());
				case LABEL -> new LabelSymbol(ref.name(), offset);
			};
		}
		if(printed == null) return null;
		int line = lineOf(text, offset);
		for(var m : printed.methods()){
			for(var insn : m.insns){
				if(insn.textStart() == line && insn.operand() instanceof VarOperand v) return new LocalSymbol(m, v.slot(), v.local());
			}
			for(var info : m.infos){
				if(info.textStart() == line && info instanceof VarInfo v) return new LocalSymbol(m, v.local().slot(), v.local());
			}
		}
		return null;
	}

	// ------------------------------------------------------------ usages

	/**
	 * @param sameOwner whether a member referenced through the first class is the member of the second: the same
	 *                  class, or for searches from the source, also a subclass
	 */
	public static List<Usage> find(CharSequence text, Symbol symbol, BiPredicate<String, String> sameOwner){
		var lines = new Lines(text);
		return switch(symbol){
			case LocalSymbol l -> locals(lines, l);
			case LabelSymbol l -> BytecodeReference.labelOccurrences(text, l.offset(), l.name()).stream()
			                                       .map(r -> new Usage(r, lines.of(r.getStartOffset()), false, lines.text(lines.of(r.getStartOffset())).strip().equals(r.subSequence(text).toString())))
			                                       .toList();
			case ClassSymbol c -> refs(lines, r -> r.kind() == BytecodeReference.Kind.CLASS && r.owner().equals(c.internalName()));
			case MemberSymbol m -> refs(lines, r -> r.kind() == m.kind() && r.name().equals(m.name()) &&
			                                        descriptorMatches(m.name(), m.descriptor(), r.descriptor()) &&
			                                        (r.owner().equals(m.owner()) || sameOwner.test(r.owner(), m.owner())));
		};
	}

	/**
	 * Constructors of inner classes and enums get parameters javac adds in front, which the source does not have.
	 */
	private static boolean descriptorMatches(String name, @Nullable String searched, @Nullable String found){
		if(searched == null || found == null || searched.equals(found)) return true;
		if(!name.equals("<init>") || !searched.startsWith("(") || !found.startsWith("(")) return false;
		return found.endsWith(searched.substring(1));
	}

	private static List<Usage> refs(Lines lines, java.util.function.Predicate<BytecodeReference> matches){
		List<Usage> out = new ArrayList<>();
		for(var r : BytecodeReference.all(lines.text, BytecodeReference.declaredClass(lines.text))){
			if(!matches.test(r)) continue;
			int    line = lines.of(r.range().getStartOffset());
			String l    = lines.text(line).strip();
			boolean put = r.kind() == BytecodeReference.Kind.FIELD && (l.startsWith("PUTFIELD ") || l.startsWith("PUTSTATIC "));
			out.add(new Usage(r.range(), line, put, r.declaration()));
		}
		return out;
	}

	private static List<Usage> locals(Lines lines, LocalSymbol l){
		List<Usage> out = new ArrayList<>();
		for(var insn : l.method().insns){
			if(!(insn.operand() instanceof VarOperand v) || v.slot() != l.slot()) continue;
			if(l.local() != null && !Objects.equals(v.local(), l.local())) continue;
			var range = operandRange(lines, insn.textStart());
			if(range == null) continue;
			int op = insn.opcode();
			out.add(new Usage(range, insn.textStart(), op>=Opcodes.ISTORE && op<=Opcodes.ASTORE || op == Opcodes.IINC, false));
		}
		for(var info : l.method().infos){
			if(!(info instanceof VarInfo v) || v.local().slot() != l.slot()) continue;
			if(l.local() != null && !v.local().equals(l.local())) continue;
			// LOCALVARIABLE name ...: the name
			int    start = lines.start(info.textStart());
			String line  = lines.text(info.textStart());
			int    name  = line.indexOf("LOCALVARIABLE ") + "LOCALVARIABLE ".length();
			out.add(new Usage(new TextRange(start + name, start + name + v.local().name().length()), info.textStart(), false, true));
		}
		out.sort(java.util.Comparator.comparingInt(u -> u.range().getStartOffset()));
		return out;
	}

	/** The slot number of a load, store or IINC: the first operand */
	private static @Nullable TextRange operandRange(Lines lines, int line){
		int    start = lines.start(line);
		String l     = lines.text(line);
		int    i     = 0;
		while(i<l.length() && l.charAt(i) == ' ') i++;
		while(i<l.length() && l.charAt(i) != ' ') i++;
		while(i<l.length() && l.charAt(i) == ' ') i++;
		int e = i;
		while(e<l.length() && Character.isDigit(l.charAt(e))) e++;
		return e>i? new TextRange(start + i, start + e) : null;
	}

	/** Line starts, found once per search */
	private static final class Lines{
		final CharSequence text;
		final int[]        starts;

		Lines(CharSequence text){
			this.text = text;
			int n = 1;
			for(int i = 0; i<text.length(); i++) if(text.charAt(i) == '\n') n++;
			starts = new int[n];
			for(int i = 0, l = 1; i<text.length(); i++) if(text.charAt(i) == '\n') starts[l++] = i + 1;
		}

		int of(int offset){
			int i = java.util.Arrays.binarySearch(starts, offset);
			return i>=0? i : -i - 2;
		}

		int start(int line){
			return starts[Math.min(line, starts.length - 1)];
		}

		String text(int line){
			int s = start(line), e = line + 1<starts.length? starts[line + 1] - 1 : text.length();
			return text.subSequence(s, Math.max(s, e)).toString();
		}
	}

	// ------------------------------------------------------------ lines

	private static int lineOf(CharSequence text, int offset){
		int line = 0;
		for(int i = 0; i<offset && i<text.length(); i++) if(text.charAt(i) == '\n') line++;
		return line;
	}

	/** The method a line belongs to, or null for lines outside methods */
	public static @Nullable Method methodAt(@Nullable Result printed, int line){
		if(printed == null) return null;
		for(var m : printed.methods()){
			int first = Integer.MAX_VALUE, last = -1;
			for(var list : List.of(m.insns, m.frames, m.infos)){
				for(var p : list){
					first = Math.min(first, p.textStart());
					last = Math.max(last, p.textEnd());
				}
			}
			// The header line is right before the body
			if(line>=first - 1 && line<=last) return m;
		}
		return null;
	}
}
