package org.objectweb.asm.idea.highlight;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import static org.objectweb.asm.idea.highlight.BytecodeTokens.*;

/**
 * Something in the bytecode text that refers to a declaration elsewhere: a class, method, field, or a label.
 *
 * @param owner      internal name of the class; for {@link Kind#CLASS} the class itself
 * @param name       member or label name, null for classes
 * @param descriptor method descriptor, null if not known
 * @param range       the text to underline
 * @param declaration whether this is where the method or field is declared, rather than a use of it
 */
public record BytecodeReference(Kind kind, @Nullable String owner, @Nullable String name, @Nullable String descriptor, TextRange range, boolean declaration){

	public enum Kind{CLASS, METHOD, FIELD, LABEL}

	private record Tok(IElementType type, int start, int end){ }

	/**
	 * @param declaringClass internal name of the class the text is about, for member declarations; null if unknown
	 */
	public static @Nullable BytecodeReference at(CharSequence text, int offset, @Nullable String declaringClass){
		if(offset<0 || offset>text.length()) return null;
		int lineStart = offset, lineEnd = offset;
		while(lineStart>0 && text.charAt(lineStart - 1) != '\n') lineStart--;
		while(lineEnd<text.length() && text.charAt(lineEnd) != '\n') lineEnd++;

		List<Tok> toks = new ArrayList<>();
		LineTokenizer.tokenize(text, lineStart, lineEnd, (type, start, end) -> toks.add(new Tok(type, start, end)));
		int i = indexAt(toks, offset);
		return i<0? null : fromToken(text, toks, i, declaringClass);
	}

	/**
	 * Every reference in the text, in order. A class name counts once, wherever it is clicked.
	 */
	public static List<BytecodeReference> all(CharSequence text, @Nullable String declaringClass){
		List<BytecodeReference> out = new ArrayList<>();
		List<Tok>               toks = new ArrayList<>();
		int                     pos  = 0;
		while(pos<=text.length()){
			int end = pos;
			while(end<text.length() && text.charAt(end) != '\n') end++;
			toks.clear();
			LineTokenizer.tokenize(text, pos, end, (type, start, e) -> toks.add(new Tok(type, start, e)));
			for(int i = 0; i<toks.size(); i++){
				var t = toks.get(i);
				if(isClassPart(t)){
					// Only where a name starts
					boolean continues = i>0 && isClassPart(toks.get(i - 1)) && toks.get(i - 1).end == t.start && !isSemicolon(text, toks.get(i - 1));
					if(continues || isSemicolon(text, t)) continue;
				}
				var ref = fromToken(text, toks, i, declaringClass);
				if(ref != null) out.add(ref);
			}
			pos = end + 1;
		}
		return out;
	}

	private static @Nullable BytecodeReference fromToken(CharSequence text, List<Tok> toks, int i, @Nullable String declaringClass){
		Tok t = toks.get(i);
		var s = text.subSequence(t.start, t.end).toString();

		if(t.type == CLASS_NAME || t.type == PACKAGE && !s.equals(";")){
			return classRun(text, toks, i);
		}
		if(t.type == LABEL){
			return new BytecodeReference(Kind.LABEL, null, s, null, new TextRange(t.start, t.end), isLabelLine(text, new TextRange(t.start, t.end)));
		}
		boolean member = i>=2 && toks.get(i - 1).type == PUNCTUATION && text.charAt(toks.get(i - 1).start) == '.';
		if(t.type == METHOD_CALL || t.type == METHOD_DECL){
			String owner = member? ownerBefore(text, toks, i - 1) : t.type == METHOD_DECL? declaringClass : null;
			if(owner == null) return null;
			return new BytecodeReference(Kind.METHOD, owner, s, descriptorAfter(text, toks, i + 1), new TextRange(t.start, t.end), t.type == METHOD_DECL);
		}
		if(t.type == FIELD){
			String owner = member? ownerBefore(text, toks, i - 1) : declaringClass;
			if(owner == null) return null;
			return new BytecodeReference(Kind.FIELD, owner, s, null, new TextRange(t.start, t.end), !member);
		}
		return null;
	}

	/** The token under the offset, or ending at it when the caret is right after a word */
	private static int indexAt(List<Tok> toks, int offset){
		for(int i = 0; i<toks.size(); i++){
			var t = toks.get(i);
			if(t.start<=offset && offset<t.end && t.type != TokenType.WHITE_SPACE) return i;
		}
		for(int i = 0; i<toks.size(); i++){
			var t = toks.get(i);
			if(t.end == offset && t.type != TokenType.WHITE_SPACE) return i;
		}
		return -1;
	}

	/** A class name split into package and name tokens, possibly as a descriptor: Lpkg/Name; */
	private static BytecodeReference classRun(CharSequence text, List<Tok> toks, int i){
		int first = i, last = i;
		// Descriptors can follow each other directly, a ; ends one
		while(first>0 && isClassPart(toks.get(first - 1)) && toks.get(first - 1).end == toks.get(first).start && !isSemicolon(text, toks.get(first - 1))) first--;
		while(last + 1<toks.size() && isClassPart(toks.get(last + 1)) && toks.get(last + 1).start == toks.get(last).end && !isSemicolon(text, toks.get(last))) last++;
		int    start = toks.get(first).start, end = toks.get(last).end;
		String name  = text.subSequence(start, end).toString();
		if(name.endsWith(";")){
			// Descriptor: drop the L and ;
			name = name.substring(1, name.length() - 1);
			end--;
			start++;
		}
		return new BytecodeReference(Kind.CLASS, name, null, null, new TextRange(start, end), false);
	}

	private static boolean isSemicolon(CharSequence text, Tok t){
		return t.end - t.start == 1 && text.charAt(t.start) == ';';
	}

	private static boolean isClassPart(Tok t){
		return t.type == PACKAGE || t.type == CLASS_NAME;
	}

	/** @param dot index of the . between owner and member */
	private static @Nullable String ownerBefore(CharSequence text, List<Tok> toks, int dot){
		if(dot<1 || !isClassPart(toks.get(dot - 1))) return null;
		return classRun(text, toks, dot - 1).owner;
	}

	/** The method descriptor following a method name, directly or after a space */
	private static @Nullable String descriptorAfter(CharSequence text, List<Tok> toks, int i){
		if(i<toks.size() && toks.get(i).type == TokenType.WHITE_SPACE) i++;
		if(i>=toks.size() || toks.get(i).type != PARENS || text.charAt(toks.get(i).start) != '(') return null;
		int start = toks.get(i).start, end = start;
		for(; i<toks.size(); i++){
			var t = toks.get(i);
			if(t.start != end || !(t.type == PARENS || t.type == PRIMITIVE || t.type == BRACKETS || isClassPart(t))) break;
			end = t.end;
		}
		return text.subSequence(start, end).toString();
	}

	/**
	 * @return the internal name of the class the text describes, from its header line
	 */
	public static @Nullable String declaredClass(CharSequence text){
		int pos = 0;
		while(pos<text.length()){
			int end = pos;
			while(end<text.length() && text.charAt(end) != '\n') end++;
			if(end>pos && text.charAt(pos) != '/' && text.charAt(pos) != ' '){
				List<Tok> toks = new ArrayList<>();
				LineTokenizer.tokenize(text, pos, end, (type, start, e) -> toks.add(new Tok(type, start, e)));
				for(int i = 0; i<toks.size(); i++){
					if(toks.get(i).type == CLASS_NAME) return classRun(text, toks, i).owner;
				}
				return null;
			}
			pos = end + 1;
		}
		return null;
	}

	/**
	 * Where a label is placed: the line that is just the label, within the method around the offset.
	 *
	 * @return the range of the label on that line, or null if not found
	 */
	public static @Nullable TextRange labelDefinition(CharSequence text, int offset, String label){
		for(var r : labelOccurrences(text, offset, label)){
			if(isLabelLine(text, r)) return r;
		}
		return null;
	}

	/**
	 * Every mention of a label in the method around the offset. Methods are blocks of text without blank lines.
	 */
	public static List<TextRange> labelOccurrences(CharSequence text, int offset, String label){
		int start = offset, end = offset;
		while(start>0 && !(text.charAt(start - 1) == '\n' && (start<2 || text.charAt(start - 2) == '\n'))) start--;
		while(end<text.length() && !(text.charAt(end) == '\n' && (end + 1>=text.length() || text.charAt(end + 1) == '\n'))) end++;
		List<TextRange> out = new ArrayList<>();
		int lineStart = start;
		while(lineStart<end){
			int lineEnd = lineStart;
			while(lineEnd<end && text.charAt(lineEnd) != '\n') lineEnd++;
			LineTokenizer.tokenize(text, lineStart, lineEnd, (type, s, e) -> {
				if(type == LABEL && e - s == label.length() && text.subSequence(s, e).toString().equals(label)) out.add(new TextRange(s, e));
			});
			lineStart = lineEnd + 1;
		}
		return out;
	}

	private static boolean isLabelLine(CharSequence text, TextRange r){
		for(int i = r.getStartOffset() - 1; i>=0 && text.charAt(i) != '\n'; i--) if(text.charAt(i) != ' ') return false;
		return r.getEndOffset() == text.length() || text.charAt(r.getEndOffset()) == '\n';
	}
}
