package org.objectweb.asm.idea.sync;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.util.Printer;
import org.objectweb.asm.util.Textifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;

/**
 * A {@link Textifier} that remembers, for every instruction it prints, what it was and on which lines of the
 * output it ended up. Produces exactly the same text as a plain Textifier.
 */
public final class RecordingTextifier extends Textifier{
	
	/**
	 * One instruction of a method.
	 *
	 * @param owner   class of a field/method instruction, type of a type instruction, bootstrap method owner of an invokedynamic
	 * @param name    field/method name, local variable name (if known), or implementation method name of a lambda/method reference
	 * @param detail  descriptor, constant value...
	 * @param line    0-based source line, -1 if unknown
	 * @param operand everything the instruction was visited with, null for instructions without operands
	 */
	public record Insn(Method method, int ordinal, int opcode, String owner, String name, String detail, int line, int[] textLines,
	                   Operand operand) implements Printed{
		
		public int textStart(){ return textLines[0]; }
		
		public int textEnd()  { return textLines[1]; }
		
		public boolean isLambdaImpl(){
			return opcode == Opcodes.INVOKEDYNAMIC && name != null && name.startsWith("lambda$");
		}
		
		public int argCount(){
			if(detail == null || !detail.startsWith("(")) return -1;
			try{
				return Type.getArgumentCount(detail);
			}catch(RuntimeException e){
				return -1;
			}
		}
		
		@Override
		public String toString(){
			return Printer.OPCODES[opcode] + (owner == null? "" : " " + owner) + (name == null? "" : " " + name) + " @" + line;
		}
	}
	
	/**
	 * Something printed in a method body that the user can select.
	 */
	public sealed interface Printed permits Insn, Frame, Info{
		Method method();
		
		int[] textLines();
		
		default int textStart(){ return textLines()[0]; }
		
		default int textEnd()  { return textLines()[1]; }
	}
	
	/**
	 * A line of a method body that is neither an instruction nor a frame.
	 */
	public sealed interface Info extends Printed permits LabelInfo, LineInfo, VarInfo, TryCatchInfo, MaxsInfo{ }
	
	/**
	 * A label, as printed on its own line
	 */
	public record LabelInfo(Method method, String name, int[] textLines) implements Info{ }
	
	/**
	 * @param line 1-based, as printed
	 */
	public record LineInfo(Method method, int line, String label, int[] textLines) implements Info{ }
	
	public record VarInfo(Method method, LocalVariable local, String start, String end, int[] textLines) implements Info{ }
	
	/**
	 * @param type internal name of the caught exception, null for any
	 */
	public record TryCatchInfo(Method method, String start, String end, String handler, String type, int[] textLines) implements Info{ }
	
	public record MaxsInfo(Method method, int maxStack, int maxLocals, int[] textLines) implements Info{ }
	
	/**
	 * A slot of a frame holding an object created by NEW whose constructor has not run yet
	 */
	public record Uninitialized(String label, String internalName){ }
	
	/**
	 * A stack map frame. Types are as in {@link org.objectweb.asm.MethodVisitor#visitFrame}: {@link Opcodes#INTEGER}...
	 * constants, internal names, {@link Uninitialized}, and for long/double a single entry covering two slots.
	 *
	 * @param kind       {@link Opcodes#F_FULL}, {@link Opcodes#F_SAME}... ({@link Opcodes#F_NEW} for expanded frames)
	 * @param ordinal    the number of instructions before it
	 * @param written    what the frame itself lists: changed locals for APPEND, all locals for FULL, nothing otherwise
	 * @param chopped    how many locals a CHOP frame removes
	 * @param locals     the complete locals, with the previous frames applied
	 * @param localNames names of the locals from the debug info, null entries where unknown
	 * @param stack      the complete operand stack
	 */
	public record Frame(Method method, int ordinal, int kind, List<Object> written, int chopped, List<Object> locals, String[] localNames,
	                    List<Object> stack, int line, int[] textLines) implements Printed{ }
	
	/**
	 * The operands of an instruction, as given to the method visitor.
	 */
	public sealed interface Operand{ }
	
	/**
	 * BIPUSH, SIPUSH, NEWARRAY (an {@link Opcodes#T_INT}... type code)
	 */
	public record IntOperand(int value) implements Operand{ }
	
	/**
	 * Loads, stores, IINC and RET.
	 *
	 * @param local the variable from the debug info, null if there is none
	 */
	public record VarOperand(int slot, int increment, LocalVariable local) implements Operand{ }
	
	/**
	 * NEW, ANEWARRAY, CHECKCAST, INSTANCEOF
	 */
	public record TypeOperand(String internalName) implements Operand{ }
	
	/**
	 * Field and method instructions
	 */
	public record MemberOperand(String owner, String name, String descriptor, boolean isInterface) implements Operand{ }
	
	public record IndyOperand(String name, String descriptor, Handle bootstrap, Object[] arguments) implements Operand{ }
	
	/**
	 * @param label the label name as printed, like L3
	 */
	public record JumpOperand(String label) implements Operand{ }
	
	public record LdcOperand(Object value) implements Operand{ }
	
	/**
	 * TABLESWITCH (keys are min..max) and LOOKUPSWITCH
	 */
	public record SwitchOperand(int[] keys, String[] labels, String defaultLabel) implements Operand{ }
	
	public record MultiArrayOperand(String descriptor, int dimensions) implements Operand{ }
	
	/**
	 * An entry of the local variable table
	 */
	public record LocalVariable(String name, String descriptor, String signature, int slot){ }
	
	public static final class Method{
		/**
		 * Internal name of the class declaring it
		 */
		public final String              owner;
		public final String              name;
		public final String              descriptor;
		public final int                 access;
		public final List<Insn>          insns  = new ArrayList<>();
		public final List<Frame>         frames = new ArrayList<>();
		/**
		 * Labels, line numbers, the local variable table... in printed order
		 */
		public final List<Info>          infos  = new ArrayList<>();
		/**
		 * The local variable table, empty without debug info
		 */
		public final List<LocalVariable> locals = new ArrayList<>();
		
		private final List<Method> siblings;
		
		Method(String owner, String name, String descriptor, int access, List<Method> siblings){
			this.owner = owner;
			this.name = name;
			this.descriptor = descriptor;
			this.access = access;
			this.siblings = siblings;
		}
		
		/**
		 * All methods of the class, including this one
		 */
		public List<Method> siblings(){
			return Collections.unmodifiableList(siblings);
		}
		
		public boolean isLambda(){
			return name.startsWith("lambda$");
		}
		
		@Override
		public String toString(){
			return name + descriptor;
		}
	}
	
	public record Result(String text, List<Method> methods){ }
	
	/**
	 * Text entry tagged with the instruction that produced it.
	 */
	private record Marked(String text, int[] lines){
		@Override
		public String toString(){ return text; }
	}
	
	private record LocalVar(String name, int index, Label start, Label end){ }
	
	private LocalVariable localVariable(LocalVar lv){
		for(var l : method.locals) if(l.slot() == lv.index && l.name().equals(lv.name)) return l;
		return null;
	}
	
	private final List<Method> methods;
	private       String       className;
	
	// Per method state
	private       Method              method;
	private       int                 currentLine = -1;
	private final Map<Label, Integer> labelOrdinals;
	private final List<LocalVar>      localVars;
	private final List<int[]>         varInsns; // {insn index, var slot}
	/**
	 * Locals of the last frame, starting with the implicit one of the method's parameters
	 */
	private       List<Object>        frameLocals;
	
	/**
	 * Source line as in the class file (0-based) to the line in the document shown for it, -1 if it has none
	 */
	private final IntUnaryOperator lineMap;
	
	public RecordingTextifier(){
		this(IntUnaryOperator.identity());
	}
	
	/**
	 * @param lineMap translates the class file's (0-based) line numbers into lines of the document shown as the
	 *                source, -1 for lines it does not have. For decompiled code, whose lines differ.
	 */
	public RecordingTextifier(IntUnaryOperator lineMap){
		this(new ArrayList<>(), lineMap);
	}
	
	private RecordingTextifier(List<Method> methods, IntUnaryOperator lineMap){
		super(Opcodes.ASM9);
		this.methods = methods;
		this.lineMap = lineMap;
		this.labelOrdinals = new HashMap<>();
		this.localVars = new ArrayList<>();
		this.varInsns = new ArrayList<>();
	}
	
	@Override
	protected Textifier createTextifier(){
		return new RecordingTextifier(methods, lineMap);
	}
	
	@Override
	public void visit(int version, int access, String name, String signature, String superName, String[] interfaces){
		super.visit(version, access, name, signature, superName, interfaces);
		className = name;
	}
	
	@Override
	public Textifier visitMethod(int access, String name, String descriptor, String signature, String[] exceptions){
		var child = (RecordingTextifier)super.visitMethod(access, name, descriptor, signature, exceptions);
		child.method = new Method(className, name, descriptor, access, methods);
		methods.add(child.method);
		return child;
	}
	
	/**
	 * Prints the whole class and resolves where each instruction is in the text.
	 */
	public Result print(){
		var sb   = new StringBuilder();
		var line = new int[]{0};
		append(getText(), sb, line);
		return new Result(sb.toString(), List.copyOf(methods));
	}
	
	private static void append(List<?> list, StringBuilder sb, int[] line){
		for(Object o : list){
			if(o instanceof List<?> l){
				append(l, sb, line);
				continue;
			}
			String s     = o.toString();
			int    start = line[0];
			sb.append(s);
			for(int i = 0; i<s.length(); i++) if(s.charAt(i) == '\n') line[0]++;
			if(o instanceof Marked m){
				int end = Math.max(start, line[0] - 1);
				if(m.lines[0]<0) m.lines[0] = start;
				m.lines[1] = Math.max(m.lines[1], end);
			}
		}
	}
	
	// ------------------------------------------------------------ recording
	
	private void record(int textFrom, int opcode, String owner, String name, String detail){
		record(textFrom, opcode, owner, name, detail, null);
	}
	
	private void record(int textFrom, int opcode, String owner, String name, String detail, Operand operand){
		if(method == null){
			return;
		}
		int[] lines = {-1, -1};
		var   insn  = new Insn(method, method.insns.size(), opcode, owner, name, detail, currentLine, lines, operand);
		method.insns.add(insn);
		mark(textFrom, lines);
	}
	
	private void mark(int textFrom, int[] lines){
		for(int i = textFrom; i<text.size(); i++){
			if(text.get(i) instanceof String s) text.set(i, new Marked(s, lines));
		}
	}
	
	@Override
	public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack){
		int from = text.size();
		super.visitFrame(type, numLocal, local, numStack, stack);
		if(method == null) return;
		if(frameLocals == null) frameLocals = implicitFrame(method);
		
		List<Object> written = new ArrayList<>(), newStack = new ArrayList<>();
		int          chopped = 0;
		switch(type){
			case Opcodes.F_NEW, Opcodes.F_FULL -> {
				for(int i = 0; i<numLocal; i++) written.add(local[i]);
				for(int i = 0; i<numStack; i++) newStack.add(stack[i]);
				frameLocals = new ArrayList<>(written);
			}
			case Opcodes.F_APPEND -> {
				for(int i = 0; i<numLocal; i++) written.add(local[i]);
				frameLocals = new ArrayList<>(frameLocals);
				frameLocals.addAll(written);
			}
			case Opcodes.F_CHOP -> {
				chopped = numLocal;
				frameLocals = new ArrayList<>(frameLocals.subList(0, Math.max(0, frameLocals.size() - numLocal)));
			}
			case Opcodes.F_SAME1 -> newStack.add(stack[0]);
			default -> { }
		}
		int[] lines = {-1, -1};
		method.frames.add(new Frame(method, method.insns.size(), type, written, chopped, new ArrayList<>(frameLocals), new String[frameLocals.size()],
		                            newStack, currentLine, lines));
		mark(from, lines);
	}
	
	/**
	 * What the locals are on entry, which the first compressed frame is relative to
	 */
	private static List<Object> implicitFrame(Method method){
		List<Object> locals = new ArrayList<>();
		if((method.access&Opcodes.ACC_STATIC) == 0){
			locals.add(method.name.equals("<init>")? Opcodes.UNINITIALIZED_THIS : method.owner);
		}
		for(Type t : Type.getArgumentTypes(method.descriptor)){
			locals.add(switch(t.getSort()){
				case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.INTEGER;
				case Type.FLOAT -> Opcodes.FLOAT;
				case Type.LONG -> Opcodes.LONG;
				case Type.DOUBLE -> Opcodes.DOUBLE;
				case Type.ARRAY -> t.getDescriptor();
				default -> t.getInternalName();
			});
		}
		return locals;
	}
	
	@Override
	public void visitInsn(int opcode){
		int from = text.size();
		super.visitInsn(opcode);
		String value = switch(opcode){
			case Opcodes.ACONST_NULL -> "null";
			case Opcodes.ICONST_M1 -> "-1";
			case Opcodes.ICONST_0, Opcodes.LCONST_0, Opcodes.FCONST_0, Opcodes.DCONST_0 -> "0";
			case Opcodes.ICONST_1, Opcodes.LCONST_1, Opcodes.FCONST_1, Opcodes.DCONST_1 -> "1";
			case Opcodes.ICONST_2, Opcodes.FCONST_2 -> "2";
			case Opcodes.ICONST_3 -> "3";
			case Opcodes.ICONST_4 -> "4";
			case Opcodes.ICONST_5 -> "5";
			default -> null;
		};
		record(from, opcode, null, null, value);
	}
	
	@Override
	public void visitIntInsn(int opcode, int operand){
		int from = text.size();
		super.visitIntInsn(opcode, operand);
		record(from, opcode, null, null, opcode == Opcodes.NEWARRAY? null : String.valueOf(operand), new IntOperand(operand));
	}
	
	@Override
	public void visitVarInsn(int opcode, int varIndex){
		int from = text.size();
		super.visitVarInsn(opcode, varIndex);
		if(method != null) varInsns.add(new int[]{method.insns.size(), varIndex});
		record(from, opcode, null, null, null, new VarOperand(varIndex, 0, null));
	}
	
	@Override
	public void visitIincInsn(int varIndex, int increment){
		int from = text.size();
		super.visitIincInsn(varIndex, increment);
		if(method != null) varInsns.add(new int[]{method.insns.size(), varIndex});
		record(from, Opcodes.IINC, null, null, String.valueOf(increment), new VarOperand(varIndex, increment, null));
	}
	
	@Override
	public void visitTypeInsn(int opcode, String type){
		int from = text.size();
		super.visitTypeInsn(opcode, type);
		record(from, opcode, type, null, null, new TypeOperand(type));
	}
	
	@Override
	public void visitFieldInsn(int opcode, String owner, String name, String descriptor){
		int from = text.size();
		super.visitFieldInsn(opcode, owner, name, descriptor);
		record(from, opcode, owner, name, descriptor, new MemberOperand(owner, name, descriptor, false));
	}
	
	@Override
	public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface){
		int from = text.size();
		super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
		record(from, opcode, owner, name, descriptor, new MemberOperand(owner, name, descriptor, isInterface));
	}
	
	@Override
	public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments){
		int from = text.size();
		super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
		String implName = name;
		// LambdaMetafactory: the second argument is the method the lambda/method reference calls
		if(bootstrapMethodArguments.length>=2 && bootstrapMethodArguments[1] instanceof Handle impl){
			implName = impl.getName();
		}
		record(from, Opcodes.INVOKEDYNAMIC, bootstrapMethodHandle.getOwner(), implName, descriptor,
		       new IndyOperand(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments.clone()));
	}
	
	@Override
	public void visitJumpInsn(int opcode, Label label){
		int from = text.size();
		super.visitJumpInsn(opcode, label);
		record(from, opcode, null, null, null, new JumpOperand(labelName(label)));
	}
	
	@Override
	public void visitLdcInsn(Object value){
		int from = text.size();
		super.visitLdcInsn(value);
		record(from, Opcodes.LDC, null, null, value instanceof Type t? t.getClassName() : String.valueOf(value), new LdcOperand(value));
	}
	
	@Override
	public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels){
		int from = text.size();
		super.visitTableSwitchInsn(min, max, dflt, labels);
		int[] keys = new int[labels.length];
		for(int i = 0; i<keys.length; i++) keys[i] = min + i;
		record(from, Opcodes.TABLESWITCH, null, null, null, new SwitchOperand(keys, labelNames(labels), labelName(dflt)));
	}
	
	@Override
	public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels){
		int from = text.size();
		super.visitLookupSwitchInsn(dflt, keys, labels);
		record(from, Opcodes.LOOKUPSWITCH, null, null, null, new SwitchOperand(keys.clone(), labelNames(labels), labelName(dflt)));
	}
	
	@Override
	public void visitMultiANewArrayInsn(String descriptor, int numDimensions){
		int from = text.size();
		super.visitMultiANewArrayInsn(descriptor, numDimensions);
		record(from, Opcodes.MULTIANEWARRAY, descriptor, null, null, new MultiArrayOperand(descriptor, numDimensions));
	}
	
	/**
	 * The name the label was printed with. Only valid once the instruction using it has been printed.
	 */
	private String labelName(Label label){
		return labelNames == null? null : labelNames.get(label);
	}
	
	private String[] labelNames(Label[] labels){
		var names = new String[labels.length];
		for(int i = 0; i<labels.length; i++) names[i] = labelName(labels[i]);
		return names;
	}
	
	/**
	 * Names the locals of frames and replaces labels of uninitialized objects, both only known at the end.
	 */
	private void resolveFrames(){
		for(var f : method.frames){
			resolveUninitialized(f.locals());
			resolveUninitialized(f.written());
			resolveUninitialized(f.stack());
			int slot = 0;
			for(int i = 0; i<f.locals().size(); i++){
				for(var lv : localVars){
					if(lv.index != slot) continue;
					Integer start = labelOrdinals.get(lv.start), end = labelOrdinals.get(lv.end);
					if(start != null && end != null && start<=f.ordinal() && f.ordinal()<end){
						f.localNames()[i] = lv.name;
						break;
					}
				}
				Object t = f.locals().get(i);
				slot += t == Opcodes.LONG || t == Opcodes.DOUBLE? 2 : 1;
			}
		}
	}
	
	private void resolveUninitialized(List<Object> types){
		for(int i = 0; i<types.size(); i++){
			if(!(types.get(i) instanceof Label l)) continue;
			Integer ordinal = labelOrdinals.get(l);
			String  type    = null;
			if(ordinal != null && ordinal<method.insns.size() && method.insns.get(ordinal).operand() instanceof TypeOperand t)
				type = t.internalName();
			types.set(i, new Uninitialized(String.valueOf(labelName(l)), type));
		}
	}
	
	private void recordInfo(int textFrom, Function<int[], Info> info){
		if(method == null) return;
		int[] lines = {-1, -1};
		method.infos.add(info.apply(lines));
		mark(textFrom, lines);
	}
	
	@Override
	public void visitLabel(Label label){
		int from = text.size();
		super.visitLabel(label);
		if(method != null) labelOrdinals.put(label, method.insns.size());
		recordInfo(from, lines -> new LabelInfo(method, labelName(label), lines));
	}
	
	@Override
	public void visitLineNumber(int line, Label start){
		int from = text.size();
		super.visitLineNumber(line, start);
		currentLine = lineMap.applyAsInt(line - 1);
		recordInfo(from, lines -> new LineInfo(method, line, labelName(start), lines));
	}
	
	@Override
	public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index){
		int from = text.size();
		super.visitLocalVariable(name, descriptor, signature, start, end, index);
		localVars.add(new LocalVar(name, index, start, end));
		if(method == null) return;
		var local = new LocalVariable(name, descriptor, signature, index);
		method.locals.add(local);
		recordInfo(from, lines -> new VarInfo(method, local, labelName(start), labelName(end), lines));
	}
	
	@Override
	public void visitTryCatchBlock(Label start, Label end, Label handler, String type){
		int from = text.size();
		super.visitTryCatchBlock(start, end, handler, type);
		recordInfo(from, lines -> new TryCatchInfo(method, labelName(start), labelName(end), labelName(handler), type, lines));
	}
	
	@Override
	public void visitMaxs(int maxStack, int maxLocals){
		int from = text.size();
		super.visitMaxs(maxStack, maxLocals);
		recordInfo(from, lines -> new MaxsInfo(method, maxStack, maxLocals, lines));
	}
	
	@Override
	public void visitMethodEnd(){
		super.visitMethodEnd();
		if(method == null) return;
		resolveFrames();
		// Local variable names are only known once the whole method has been visited
		for(int[] v : varInsns){
			int ordinal = v[0], slot = v[1];
			for(var lv : localVars){
				if(lv.index != slot) continue;
				Integer start = labelOrdinals.get(lv.start), end = labelOrdinals.get(lv.end);
				if(start == null || end == null) continue;
				// A store happens right before the variable's scope begins
				if(start<=ordinal + 1 && ordinal<end){
					var old = method.insns.get(ordinal);
					var op  = (VarOperand)old.operand();
					method.insns.set(ordinal, new Insn(old.method(), old.ordinal(), old.opcode(), old.owner(), lv.name, old.detail(), old.line(), old.textLines(),
					                                   new VarOperand(op.slot(), op.increment(), localVariable(lv))));
					break;
				}
			}
		}
	}
}
