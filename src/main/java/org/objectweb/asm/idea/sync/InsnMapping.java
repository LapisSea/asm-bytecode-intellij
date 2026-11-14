package org.objectweb.asm.idea.sync;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.util.ClassUtil;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.idea.OutlineLoader;
import org.objectweb.asm.idea.sync.RecordingTextifier.Insn;
import org.objectweb.asm.idea.sync.RecordingTextifier.Method;
import org.objectweb.asm.idea.sync.SourceAnchors.Anchor;
import org.objectweb.asm.idea.sync.SourceAnchors.Kind;
import org.objectweb.asm.idea.sync.SourceAnchors.Mode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.objectweb.asm.Opcodes.*;

/**
 * Maps individual bytecode instructions to the Java expressions they were compiled from.
 * <p>
 * Class files only record line numbers, so this is reconstructed: the instructions of each method and the
 * expressions of its source are both listed in evaluation order and aligned ({@link Aligner}), matching calls by
 * name, field and variable accesses by name, operators by kind and so on. Instructions with no expression of their
 * own (DUP, POP, boxing...) are attached to a neighbour. Anything that cannot be matched is left out, and callers
 * fall back to whole line matching.
 */
public final class InsnMapping{
	
	private final long                       sourceStamp;
	private final Insn[]                     byTextLine;
	private final Map<Insn, Anchor>          anchors;
	private final Map<TextRange, List<Insn>> byRange;
	private final Map<Method, TextRange>     lambdaBodies;
	private final List<Insn>                 all;
	
	private InsnMapping(long sourceStamp, Insn[] byTextLine, Map<Insn, Anchor> anchors, Map<TextRange, List<Insn>> byRange,
	                    Map<Method, TextRange> lambdaBodies, List<Insn> all){
		this.sourceStamp = sourceStamp;
		this.byTextLine = byTextLine;
		this.anchors = anchors;
		this.byRange = byRange;
		this.lambdaBodies = lambdaBodies;
		this.all = all;
	}
	
	/**
	 * Must be called in a read action.
	 *
	 * @param className internal name of the class the bytecode is for
	 * @return null if the source cannot be matched (not Java, class not found...)
	 */
	public static @Nullable InsnMapping build(PsiFile file, Document document, String className, RecordingTextifier.Result printed){
		if(!(file instanceof PsiJavaFile) || !file.isValid()) return null;
		PsiClass psiClass = findClass(file, className.replace('/', '.'));
		if(psiClass == null) return null;
		
		var anchors      = new HashMap<Insn, Anchor>();
		var lambdaPsi    = new HashMap<String, PsiElement>();
		var lambdaBodies = new HashMap<Method, TextRange>();
		var pending      = new ArrayList<Method>();
		
		for(var method : printed.methods()){
			if(method.isLambda()){
				pending.add(method);
				continue;
			}
			var roots = rootsFor(method, psiClass);
			if(roots != null) alignMethod(method, SourceAnchors.collect(document, roots), anchors, lambdaPsi);
		}
		// Lambdas are found through the invokedynamic that creates them, which can be in another lambda
		boolean progress = true;
		while(progress && !pending.isEmpty()){
			progress = false;
			for(var it = pending.iterator(); it.hasNext(); ){
				var method = it.next();
				var psi    = lambdaPsi.get(method.name);
				if(psi == null) continue;
				it.remove();
				progress = true;
				List<Anchor> sourceAnchors;
				if(psi instanceof PsiLambdaExpression l && l.getBody() != null){
					lambdaBodies.put(method, l.getBody().getTextRange());
					sourceAnchors = SourceAnchors.collect(document, List.of(l.getBody()));
				}else if(psi instanceof PsiMethodReferenceExpression ref){
					// A method reference javac had to wrap in a lambda: its body is the call
					lambdaBodies.put(method, ref.getTextRange());
					var kind = ref.isConstructor()? Kind.INIT : Kind.INVOKE;
					var name = ref.isConstructor()? "<init>" : ref.getReferenceName();
					sourceAnchors = List.of(new Anchor(kind, name, -1, document.getLineNumber(ref.getTextRange().getStartOffset()),
					                                   ref.getTextRange(), ref, Mode.ANY));
				}else{
					continue;
				}
				alignMethod(method, sourceAnchors, anchors, lambdaPsi);
			}
		}
		
		// Group by highlighted range: a field initializer is compiled into every constructor, so one range can have many instructions
		var byRange = new HashMap<TextRange, List<Insn>>();
		for(var e : anchors.entrySet()){
			byRange.computeIfAbsent(e.getValue().own(), k -> new ArrayList<>()).add(e.getKey());
		}
		// Selecting a lambda also selects its body
		var lambdaMethods = new HashMap<String, Method>();
		for(var m : printed.methods()) if(m.isLambda()) lambdaMethods.put(m.name, m);
		for(var e : anchors.entrySet()){
			var insn = e.getKey();
			if(insn.isLambdaImpl() && (e.getValue().kind() == Kind.LAMBDA || e.getValue().kind() == Kind.METHOD_REF)){
				var body = lambdaMethods.get(insn.name());
				if(body != null) byRange.get(e.getValue().own()).addAll(body.insns);
			}
		}
		
		int lineCount = (int)printed.text().chars().filter(c -> c == '\n').count() + 1;
		var byLine    = new Insn[lineCount];
		var all       = new ArrayList<Insn>();
		for(var m : printed.methods()){
			for(var insn : m.insns){
				all.add(insn);
				if(insn.textStart()<0) continue;
				for(int l = insn.textStart(); l<=insn.textEnd() && l<lineCount; l++) byLine[l] = insn;
			}
		}
		return new InsnMapping(document.getModificationStamp(), byLine, anchors, byRange, lambdaBodies, all);
	}
	
	/**
	 * @return where the body of a lambda (or the method reference) compiled into the given synthetic method is, null if unknown
	 */
	public @Nullable TextRange lambdaBody(String methodName){
		for(var e : lambdaBodies.entrySet()){
			if(e.getKey().name.equals(methodName)) return e.getValue();
		}
		return null;
	}
	
	public boolean isValidFor(Document sourceDocument){
		return sourceDocument.getModificationStamp() == sourceStamp;
	}
	
	/**
	 * @return the bytecode for the source expression at the offset, or the bytecode of the line, limited to the
	 * method the offset is in (so code in lambdas on the same line is told apart). Null if nothing is known.
	 */
	public @Nullable List<LineMapping.Range> bytecodeFor(int offset, int line){
		TextRange best = null;
		for(var range : byRange.keySet()){
			if(range.getStartOffset()<=offset && offset<=range.getEndOffset()){
				if(best == null || range.getLength()<best.getLength()) best = range;
			}
		}
		if(best != null) return toRanges(byRange.get(best));
		
		Method    context      = null;
		TextRange contextRange = null;
		for(var e : lambdaBodies.entrySet()){
			var r = e.getValue();
			if(r.getStartOffset()<=offset && offset<=r.getEndOffset() && (contextRange == null || r.getLength()<contextRange.getLength())){
				context = e.getKey();
				contextRange = r;
			}
		}
		var insns = new ArrayList<Insn>();
		for(var insn : all){
			if(insn.line() != line) continue;
			if(context == null? insn.method().isLambda() : insn.method() != context) continue;
			insns.add(insn);
		}
		return insns.isEmpty()? null : toRanges(insns);
	}
	
	/**
	 * @return the source range of the expression the instruction on this bytecode text line was compiled from
	 */
	public @Nullable TextRange sourceFor(int bytecodeLine){
		if(bytecodeLine<0 || bytecodeLine>=byTextLine.length) return null;
		var insn = byTextLine[bytecodeLine];
		if(insn == null) return null;
		var anchor = anchors.get(insn);
		return anchor == null? null : anchor.own();
	}
	
	private static List<LineMapping.Range> toRanges(Collection<Insn> insns){
		var sorted = insns.stream().filter(i -> i.textStart()>=0).sorted(Comparator.comparingInt(Insn::textStart)).toList();
		var result = new ArrayList<LineMapping.Range>();
		int start  = -1, end = -1;
		for(var insn : sorted){
			if(start != -1 && insn.textStart()<=end + 1){
				end = Math.max(end, insn.textEnd());
				continue;
			}
			if(start != -1) result.add(new LineMapping.Range(start, end));
			start = insn.textStart();
			end = insn.textEnd();
		}
		if(start != -1) result.add(new LineMapping.Range(start, end));
		return result;
	}
	
	// ------------------------------------------------------------ alignment
	
	private static void alignMethod(Method method, List<Anchor> sourceAnchors, Map<Insn, Anchor> out, Map<String, PsiElement> lambdaPsi){
		var insns = method.insns;
		if(insns.isEmpty() || sourceAnchors.isEmpty()) return;
		
		int[] match;
		if((long)insns.size()*sourceAnchors.size()<=Aligner.MAX_CELLS){
			match = Aligner.align(insns.size(), sourceAnchors.size(), (i, j) -> score(insns.get(i), sourceAnchors.get(j)));
		}else{
			match = alignByLine(insns, sourceAnchors);
		}
		Aligner.attachUnmatched(match, i -> insns.get(i).line(), i -> attachesForward(insns.get(i).opcode()));
		
		for(int i = 0; i<match.length; i++){
			if(match[i] == -1) continue;
			var insn   = insns.get(i);
			var anchor = sourceAnchors.get(match[i]);
			out.put(insn, anchor);
			if(insn.isLambdaImpl() && (anchor.kind() == Kind.LAMBDA || anchor.kind() == Kind.METHOD_REF)){
				lambdaPsi.put(insn.name(), anchor.element());
			}
		}
	}
	
	/**
	 * Fallback for huge methods: align each source line on its own.
	 */
	private static int[] alignByLine(List<Insn> insns, List<Anchor> anchors){
		int[] match = new int[insns.size()];
		Arrays.fill(match, -1);
		var byLine = new HashMap<Integer, List<Integer>>();
		for(int j = 0; j<anchors.size(); j++) byLine.computeIfAbsent(anchors.get(j).line(), k -> new ArrayList<>()).add(j);
		var insnsByLine = new LinkedHashMap<Integer, List<Integer>>();
		for(int i = 0; i<insns.size(); i++) insnsByLine.computeIfAbsent(insns.get(i).line(), k -> new ArrayList<>()).add(i);
		for(var e : insnsByLine.entrySet()){
			var as = byLine.get(e.getKey());
			if(as == null) continue;
			var is = e.getValue();
			if((long)is.size()*as.size()>Aligner.MAX_CELLS) continue;
			int[] m = Aligner.align(is.size(), as.size(), (i, j) -> score(insns.get(is.get(i)), anchors.get(as.get(j))));
			for(int k = 0; k<m.length; k++) if(m[k] != -1) match[is.get(k)] = as.get(m[k]);
		}
		return match;
	}
	
	private static boolean attachesForward(int opcode){
		return opcode>=ACONST_NULL && opcode<=LDC ||   // constants
		       opcode>=ILOAD && opcode<=ALOAD ||       // loads
		       opcode>=DUP && opcode<=SWAP ||
		       opcode == NEW || opcode == GETSTATIC;
	}
	
	static int score(Insn insn, Anchor anchor){
		int base = baseScore(insn, anchor);
		if(base<=0) return 0;
		if(insn.line()>=0){
			if(insn.line() == anchor.line()) base += 3;
			else base = Math.max(1, base - 2);
		}
		return base;
	}
	
	private static int baseScore(Insn insn, Anchor a){
		int op = insn.opcode();
		return switch(a.kind()){
			case INVOKE -> op>=INVOKEVIRTUAL && op<=INVOKEINTERFACE && !"<init>".equals(insn.name()) && Objects.equals(insn.name(), a.name())?
			               10 + (a.argCount()>=0 && a.argCount() == insn.argCount()? 2 : 0) : 0;
			case INIT -> op == INVOKESPECIAL && "<init>".equals(insn.name())? 8 : 0;
			case NEW -> op == NEW? 6 + (a.name() != null && simpleName(insn.owner()).equals(a.name())? 3 : 0) : 0;
			case LAMBDA -> op == INVOKEDYNAMIC && insn.isLambdaImpl()? 9 : 0;
			case METHOD_REF -> {
				if(op != INVOKEDYNAMIC) yield 0;
				if(insn.isLambdaImpl()) yield 4;
				yield Objects.equals(insn.name(), a.name())? 10 : 0;
			}
			case CONCAT -> {
				if(op == INVOKEDYNAMIC && "java/lang/invoke/StringConcatFactory".equals(insn.owner())) yield 8;
				yield op == INVOKEVIRTUAL && "java/lang/StringBuilder".equals(insn.owner())? 2 : 0;
			}
			case FIELD -> {
				boolean read = op == GETFIELD || op == GETSTATIC, write = op == PUTFIELD || op == PUTSTATIC;
				if(!(read || write) || !Objects.equals(insn.name(), a.name())) yield 0;
				yield a.mode() == Mode.ANY || (a.mode() == Mode.READ) == read? 10 : 0;
			}
			case VAR -> {
				boolean load = op>=ILOAD && op<=ALOAD, store = op>=ISTORE && op<=ASTORE, inc = op == IINC;
				if(!(load || store || inc)) yield 0;
				boolean modeOk = a.mode() == Mode.ANY || inc || (a.mode() == Mode.READ) == load;
				if(!modeOk) yield 0;
				if(insn.name() == null) yield 3;
				yield insn.name().equals(a.name())? 10 : 0;
			}
			case CONST -> op>=ACONST_NULL && op<=LDC? 5 + (Objects.equals(insn.detail(), a.name())? 3 : 0) : 0;
			case BINOP -> op>=IADD && op<=LXOR && !(op>=INEG && op<=DNEG)? 5 + (binop(op).equals(a.name())? 2 : 0) : 0;
			case NEG -> op>=INEG && op<=DNEG? 6 : 0;
			case CONVERT -> op>=I2L && op<=I2S? 5 : 0;
			case JUMP -> op>=IFEQ && op<=IF_ACMPNE || op == IFNULL || op == IFNONNULL? 4 : op>=LCMP && op<=DCMPG? 3 : 0;
			case SWITCH -> op == TABLESWITCH || op == LOOKUPSWITCH ||
			               op == INVOKEDYNAMIC && "java/lang/runtime/SwitchBootstraps".equals(insn.owner())? 6 : 0;
			case CHECKCAST -> op == CHECKCAST? 5 + (a.name() != null && simpleName(insn.owner()).equals(a.name())? 2 : 0) : 0;
			case INSTANCEOF -> op == INSTANCEOF? 6 : 0;
			case ARRAY_LOAD -> op>=IALOAD && op<=SALOAD? 6 : 0;
			case ARRAY_STORE -> op>=IASTORE && op<=SASTORE? 6 : 0;
			case ARRAY_LENGTH -> op == ARRAYLENGTH? 6 : 0;
			case NEW_ARRAY -> op == NEWARRAY || op == ANEWARRAY || op == MULTIANEWARRAY? 6 : 0;
			case RETURN -> op>=IRETURN && op<=Opcodes.RETURN? 5 : 0;
			case THROW -> op == ATHROW? 6 : 0;
			case MONITOR -> op == MONITORENTER || op == MONITOREXIT? 5 : 0;
		};
	}
	
	private static String binop(int op){
		if(op<=DADD) return "+";
		if(op<=DSUB) return "-";
		if(op<=DMUL) return "*";
		if(op<=DDIV) return "/";
		if(op<=DREM) return "%";
		if(op<=LSHL) return "<<";
		if(op<=LSHR) return ">>";
		if(op<=LUSHR) return ">>>";
		if(op<=LAND) return "&";
		if(op<=LOR) return "|";
		return "^";
	}
	
	private static String simpleName(@Nullable String internalName){
		if(internalName == null) return "";
		int i = Math.max(internalName.lastIndexOf('/'), internalName.lastIndexOf('$'));
		return internalName.substring(i + 1);
	}
	
	// ------------------------------------------------------------ source structure
	
	private static @Nullable PsiClass findClass(PsiFile file, String jvmName){
		for(var c : PsiTreeUtil.findChildrenOfType(file, PsiClass.class)){
			if(c instanceof PsiTypeParameter) continue;
			if(jvmName.equals(OutlineLoader.getJVMClassName(c))) return c;
		}
		return null;
	}
	
	/**
	 * @return the source the method was compiled from, in order, or null if it has no source (synthetic, generated...)
	 */
	private static @Nullable List<PsiElement> rootsFor(Method method, PsiClass psiClass){
		if((method.access&(ACC_SYNTHETIC|ACC_BRIDGE)) != 0) return null;
		
		if(method.name.equals("<clinit>")){
			var roots = new ArrayList<PsiElement>();
			for(PsiElement c = psiClass.getFirstChild(); c != null; c = c.getNextSibling()){
				if(c instanceof PsiField f && f.hasModifierProperty(PsiModifier.STATIC)) roots.add(f);
				if(c instanceof PsiClassInitializer init && init.hasModifierProperty(PsiModifier.STATIC)) roots.add(init.getBody());
			}
			return roots;
		}
		
		if(method.name.equals("<init>")){
			var ctor = findMethod(List.of(psiClass.getConstructors()), method);
			var body = ctor == null? null : ctor.getBody();
			
			PsiStatement first = body == null || body.getStatements().length == 0? null : body.getStatements()[0];
			String call = first instanceof PsiExpressionStatement es && es.getExpression() instanceof PsiMethodCallExpression mc?
			              mc.getMethodExpression().getReferenceName() : null;
			if("this".equals(call)) return List.of(body);
			
			// super(...), then field initializers and initializer blocks, then the rest of the constructor
			var roots = new ArrayList<PsiElement>();
			if("super".equals(call)) roots.add(first);
			for(PsiElement c = psiClass.getFirstChild(); c != null; c = c.getNextSibling()){
				if(c instanceof PsiField f && !f.hasModifierProperty(PsiModifier.STATIC)) roots.add(f);
				if(c instanceof PsiClassInitializer init && !init.hasModifierProperty(PsiModifier.STATIC)) roots.add(init.getBody());
			}
			if(body != null){
				for(var st : body.getStatements()) if(st != first || !"super".equals(call)) roots.add(st);
			}
			return roots;
		}
		
		var method0 = findMethod(List.of(psiClass.findMethodsByName(method.name, false)), method);
		if(method0 == null || method0.getBody() == null) return null;
		return List.of(method0.getBody());
	}
	
	private static @Nullable PsiMethod findMethod(List<PsiMethod> candidates, Method method){
		if(candidates.isEmpty()) return null;
		for(var m : candidates){
			if(method.descriptor.equals(ClassUtil.getAsmMethodSignature(m))) return m;
		}
		if(candidates.size() == 1) return candidates.getFirst();
		// Constructors of inner classes and enums have extra synthetic parameters
		int argCount = Type.getArgumentCount(method.descriptor);
		for(int extra = 0; extra<=2; extra++){
			for(var m : candidates){
				if(m.getParameterList().getParametersCount() + extra == argCount) return m;
			}
		}
		return null;
	}
}
