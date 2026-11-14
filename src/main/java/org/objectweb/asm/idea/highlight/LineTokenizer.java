package org.objectweb.asm.idea.highlight;

import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.objectweb.asm.util.Printer;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.objectweb.asm.idea.highlight.BytecodeTokens.*;

/**
 * Splits one line of Textifier output into tokens. Every line can be read on its own, which keeps the lexer
 * restartable at any line.
 */
final class LineTokenizer{

	interface Sink{
		void token(IElementType type, int start, int end);
	}

	private static final Set<String> OPCODES = Arrays.stream(Printer.OPCODES).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());

	/** Followed by a type that may be in the default package, so not recognizable by a slash */
	private static final Set<String> TYPE_OPCODES = Set.of("NEW", "ANEWARRAY", "CHECKCAST", "INSTANCEOF");

	private static final Set<String> DIRECTIVES = Set.of(
		"LINENUMBER", "LOCALVARIABLE", "MAXSTACK", "MAXLOCALS", "TRYCATCHBLOCK", "FRAME",
		"NESTHOST", "NESTMEMBER", "INNERCLASS", "OUTERCLASS", "PERMITTEDSUBCLASS", "RECORDCOMPONENT",
		"LOCALVARIABLEANNOTATION", "INSNANNOTATION", "TRYCATCHBLOCKANNOTATION", "ATTRIBUTE"
	);

	/** Followed by class names */
	private static final Set<String> CLASS_DIRECTIVES = Set.of("NESTHOST", "NESTMEMBER", "INNERCLASS", "OUTERCLASS", "PERMITTEDSUBCLASS");

	private static final Set<String> FRAME_KINDS = Set.of("FULL", "SAME", "SAME1", "APPEND", "CHOP");

	private static final Set<String> KEYWORDS = Set.of(
		"public", "private", "protected", "static", "final", "abstract", "synchronized", "volatile", "transient", "native",
		"strictfp", "synthetic", "bridge", "varargs", "mandated", "deprecated", "enum", "interface", "class", "extends",
		"implements", "throws", "record", "module", "open", "requires", "exports", "opens", "uses", "provides", "to", "with",
		"transitive", "default", "null", "this", "super", "annotation"
	);

	/** Followed by class names */
	private static final Set<String> CLASS_KEYWORDS = Set.of("class", "interface", "extends", "implements", "throws", "enum", "record", "annotation");

	private static final String PRIMITIVES       = "ZBCSIJFDV";
	/** Frame entries: top, int, float, double, long, null, uninitialized this */
	private static final String FRAME_PRIMITIVES = "TIFDJNU";

	private final CharSequence buf;
	private final int          end;
	private final Sink         sink;
	private       int          pos;

	// What the line has had so far
	private final boolean declaration; // a class member, as opposed to a method body line
	private       boolean opcode;
	private       String  directive;
	private       boolean classContext;
	private       boolean frame;
	private       int     frameDepth;
	private       boolean type;
	private       boolean localNamed;

	private LineTokenizer(CharSequence buf, int start, int end, Sink sink){
		this.buf = buf;
		this.end = end;
		this.sink = sink;
		this.pos = start;
		int indent = 0;
		while(start + indent<end && buf.charAt(start + indent) == ' ') indent++;
		declaration = indent == 2;
	}

	/**
	 * @param end the end of the line, excluding the line break
	 */
	static void tokenize(CharSequence buf, int start, int end, Sink sink){
		new LineTokenizer(buf, start, end, sink).run();
	}

	private void emit(IElementType type, int to){
		if(to>pos) sink.token(type, pos, to);
		pos = to;
	}

	private char at(int i){
		return i<end? buf.charAt(i) : 0;
	}

	private void run(){
		while(pos<end){
			char c = buf.charAt(pos);
			if(c == ' ' || c == '\t'){
				int e = pos;
				while(e<end && (buf.charAt(e) == ' ' || buf.charAt(e) == '\t')) e++;
				emit(TokenType.WHITE_SPACE, e);
			}else if(c == '/' && at(pos + 1) == '/'){
				emit(COMMENT, end);
			}else if(c == '"'){
				string();
			}else if(c == '@'){
				annotation();
			}else if(c == '('){
				if(methodDescriptorEnd(pos)>0) methodDescriptor();
				else emit(PARENS, pos + 1);
			}else if(c == '['){
				if(frame && frameDepth == 0){
					frameDepth++;
					emit(BRACKETS, pos + 1);
				}else if(typeEnd(pos)>0){
					type();
				}else{
					emit(BRACKETS, pos + 1);
				}
			}else if(c == ']'){
				if(frameDepth>0) frameDepth--;
				emit(BRACKETS, pos + 1);
			}else if(c == ')'){
				emit(PARENS, pos + 1);
			}else if(c == '{' || c == '}'){
				emit(BRACES, pos + 1);
			}else if(Character.isDigit(c) || c == '-' && Character.isDigit(at(pos + 1))){
				number();
			}else if(isWordStart(c)){
				word();
			}else{
				emit(PUNCTUATION, pos + 1);
			}
		}
	}

	// ------------------------------------------------------------ literals

	private void string(){
		int e = pos + 1;
		while(e<end){
			char c = buf.charAt(e);
			if(c == '\\'){
				e += 2;
				continue;
			}
			e++;
			if(c == '"') break;
		}
		emit(STRING, Math.min(e, end));
	}

	private void number(){
		int e = pos;
		if(buf.charAt(e) == '-') e++;
		if(at(e) == '0' && (at(e + 1) == 'x' || at(e + 1) == 'X')){
			e += 2;
			while(Character.digit(at(e), 16)>=0) e++;
		}else{
			while(Character.isDigit(at(e))) e++;
			if(at(e) == '.' && Character.isDigit(at(e + 1))){
				e++;
				while(Character.isDigit(at(e))) e++;
			}
			if((at(e) == 'E' || at(e) == 'e') && (Character.isDigit(at(e + 1)) || at(e + 1) == '-' && Character.isDigit(at(e + 2)))){
				e += 2;
				while(Character.isDigit(at(e))) e++;
			}
		}
		if("LFDlfd".indexOf(at(e))>=0 && at(e) != 0 && !isWordPart(at(e + 1))) e++;
		emit(NUMBER, e);
	}

	private void annotation(){
		int e = pos + 1;
		if(at(e) == 'L'){
			while(e<end && buf.charAt(e) != ';' && buf.charAt(e) != ' ' && buf.charAt(e) != '(') e++;
			if(at(e) == ';') e++;
		}
		emit(ANNOTATION, e);
	}

	// ------------------------------------------------------------ descriptors

	/** @return the end of the field descriptor at i, or -1 if there is none */
	private int typeEnd(int i){
		while(at(i) == '[') i++;
		char c = at(i);
		if(c == 'L'){
			int e = i + 1;
			while(e<end && isWordPart(buf.charAt(e)) || at(e) == '/') e++;
			return e>i + 1 && at(e) == ';'? e + 1 : -1;
		}
		return c != 0 && PRIMITIVES.indexOf(c)>=0? i + 1 : -1;
	}

	/** @return the end of the method descriptor at i, or -1 if there is none */
	private int methodDescriptorEnd(int i){
		if(at(i) != '(') return -1;
		i++;
		while(at(i) != ')'){
			int e = typeEnd(i);
			if(e<0) return -1;
			i = e;
		}
		return typeEnd(i + 1);
	}

	private void type(){
		int e = pos;
		while(at(e) == '[') e++;
		emit(BRACKETS, e);
		if(at(pos) == 'L'){
			classDescriptor(typeEnd(pos));
		}else{
			emit(PRIMITIVE, pos + 1);
		}
		type = true;
	}

	private void methodDescriptor(){
		emit(PARENS, pos + 1);
		while(at(pos) != ')') type();
		emit(PARENS, pos + 1);
		type();
	}

	/** Lpackage/Name; with the package, L and ; in a quieter color */
	private void classDescriptor(int e){
		int slash = lastSlash(pos, e);
		emit(PACKAGE, slash<0? pos + 1 : slash + 1);
		emit(CLASS_NAME, e - 1);
		emit(PACKAGE, e);
	}

	/** An internal name: package/Name */
	private void className(int e){
		int slash = lastSlash(pos, e);
		if(slash>=0) emit(PACKAGE, slash + 1);
		emit(CLASS_NAME, e);
	}

	private int lastSlash(int from, int to){
		for(int i = to - 1; i>=from; i--) if(buf.charAt(i) == '/') return i;
		return -1;
	}

	// ------------------------------------------------------------ words

	private static boolean isWordStart(char c){
		return Character.isJavaIdentifierStart(c) || c == '<';
	}

	private static boolean isWordPart(char c){
		return Character.isJavaIdentifierPart(c) && c != 0 || c == '<' || c == '>';
	}

	private void word(){
		int s = pos, e = pos;
		while(e<end && (isWordPart(buf.charAt(e)) || buf.charAt(e) == '/')) e++;
		String w = buf.subSequence(s, e).toString();

		// Field descriptor: Ljava/lang/String;
		if(w.charAt(0) == 'L' && at(e) == ';' && w.length()>1){
			classDescriptor(e + 1);
			type = true;
			return;
		}
		// Member: owner.name
		if(at(e) == '.' && isWordStart(at(e + 1))){
			className(e);
			emit(PUNCTUATION, pos + 1);
			int n = pos;
			while(n<end && isWordPart(buf.charAt(n))) n++;
			int next = n;
			while(at(next) == ' ') next++;
			emit(at(next) == ':'? FIELD : METHOD_CALL, n);
			return;
		}
		// Method name with its descriptor attached
		if(at(e) == '(' && methodDescriptorEnd(e)>0){
			emit(declaration && !opcode && directive == null? METHOD_DECL : METHOD_CALL, e);
			return;
		}
		if(w.length() == 1 && (PRIMITIVES.indexOf(w.charAt(0))>=0 || frame && FRAME_PRIMITIVES.indexOf(w.charAt(0))>=0)){
			emit(PRIMITIVE, e);
			type = true;
			return;
		}
		if(!opcode && directive == null && OPCODES.contains(w)){
			opcode = true;
			classContext = TYPE_OPCODES.contains(w);
			emit(OPCODE, e);
			return;
		}
		if(directive == null && !opcode && DIRECTIVES.contains(w)){
			directive = w;
			frame = w.equals("FRAME");
			classContext = CLASS_DIRECTIVES.contains(w);
			emit(DIRECTIVE, e);
			return;
		}
		if(frame && FRAME_KINDS.contains(w)){
			emit(DIRECTIVE, e);
			return;
		}
		if(w.length()>1 && w.charAt(0) == 'L' && w.chars().skip(1).allMatch(Character::isDigit)){
			emit(LABEL, e);
			return;
		}
		if("LOCALVARIABLE".equals(directive) && !localNamed){
			localNamed = true;
			emit(LOCAL_VAR, e);
			return;
		}
		if(KEYWORDS.contains(w)){
			if(CLASS_KEYWORDS.contains(w)) classContext = true;
			emit(KEYWORD, e);
			return;
		}
		if(w.indexOf('/')>=0 || classContext || frame){
			className(e);
			return;
		}
		// The name of a field or record component after its type
		emit(declaration && !opcode && type? FIELD : IDENTIFIER, e);
	}
}
