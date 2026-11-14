package org.objectweb.asm.idea.docs;

import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.idea.sync.RecordingTextifier.*;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.util.TraceSignatureVisitor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.objectweb.asm.Opcodes.*;

/**
 * Describes in words what an instruction does with its particular operands.
 */
public final class InsnExplainer{

	/**
	 * What the source knows about a method that the bytecode does not.
	 *
	 * @param parameterNames null entries for unknown names
	 * @param parameterTypes with generics, null to use the descriptor
	 * @param returnType     with generics, null to use the descriptor
	 * @param exceptions     declared thrown types
	 */
	public record MethodInfo(List<String> parameterNames, @Nullable List<String> parameterTypes, @Nullable String returnType, List<String> exceptions){ }

	public interface Resolver{
		Resolver NONE = new Resolver(){
			@Override
			public @Nullable MethodInfo method(String owner, String name, String descriptor){ return null; }

			@Override
			public @Nullable String fieldType(String owner, String name){ return null; }
		};

		@Nullable MethodInfo method(String owner, String name, String descriptor);

		/** @return the declared type with generics */
		@Nullable String fieldType(String owner, String name);
	}

	private final Resolver      resolver;
	private final StringBuilder out = new StringBuilder();

	private InsnExplainer(Resolver resolver){
		this.resolver = resolver;
	}

	/**
	 * @return HTML, or null if the instruction does nothing beyond what its documentation says
	 */
	public static @Nullable String explain(Insn insn, Resolver resolver){
		var e = new InsnExplainer(resolver);
		e.explain(insn);
		return e.out.isEmpty()? null : e.out.toString();
	}

	private void explain(Insn insn){
		int op = insn.opcode();
		switch(insn.operand()){
			case VarOperand v -> variable(insn, op, v);
			case IntOperand i -> intOperand(op, i.value());
			case LdcOperand l -> ldc(l.value());
			case MemberOperand m when op>=GETSTATIC && op<=PUTFIELD -> field(op, m);
			case MemberOperand m -> invoke(insn, op, m);
			case IndyOperand d -> indy(insn, d);
			case JumpOperand j -> jump(op, j.label());
			case SwitchOperand s -> switchInsn(s);
			case TypeOperand t -> typeInsn(op, t.internalName());
			case MultiArrayOperand m -> p("Pops " + m.dimensions() + " array lengths and creates a new " + type(Type.getType(m.descriptor())) +
			                              (m.dimensions()<Type.getType(m.descriptor()).getDimensions()? ", leaving the inner dimensions unallocated" : "") + ".");
			case null -> noOperand(insn, op);
		}
	}

	// ------------------------------------------------------------ other lines

	private static final String DEBUG_INFO =
		"It is <b>debug information</b>, not something that executes: the JVM runs the method the same without it. " +
		"It is left out when compiling with <code>-g:none</code>, and the <i>Skip debug</i> setting hides it here.";

	/**
	 * Explains a line that is not an instruction: labels, line numbers, the local variable table, the exception table,
	 * stack and locals sizes.
	 */
	public static String explainInfo(Info info){
		var e = new InsnExplainer(Resolver.NONE);
		switch(info){
			case LabelInfo l -> e.label(l);
			case LineInfo l -> {
				e.p("Line <b>" + l.line() + "</b> of the source file: the instructions from <b>" + esc(String.valueOf(l.label())) +
				    "</b> up to the next <code>LINENUMBER</code> were compiled from it.");
				e.p(DEBUG_INFO);
				e.p("It is what puts line numbers in stack traces, and lets a debugger stop on a line and step through it. " +
				    "Caret syncing in this view uses it as well.");
			}
			case VarInfo v -> {
				var    lv   = v.local();
				String type = lv.signature() != null? signatureType(lv.signature()) : type(Type.getType(lv.descriptor()));
				e.p("Local variable <b>" + esc(lv.name()) + "</b> (" + type + ") is in slot " + lv.slot() + " from <b>" + esc(String.valueOf(v.start())) +
				    "</b> up to <b>" + esc(String.valueOf(v.end())) + "</b>, which is its scope in the source.");
				if(lv.signature() != null) e.p("The second line is its generic type, which the descriptor on the first line leaves out.");
				e.p(DEBUG_INFO);
				e.p("Debuggers use it to show variables by name. Without it, the slots still work the same; they just have no names.");
			}
			case TryCatchInfo t -> {
				String what = t.type() == null? "anything" : article(t.type()) + " " + className(t.type()) + " (or a subclass)";
				e.p("An entry of the method's <b>exception table</b>. Not an instruction, but not debug information either: " +
				    "it decides what happens when an exception is thrown.");
				e.p("If " + what + " is thrown by the instructions from <b>" + esc(String.valueOf(t.start())) + "</b> up to <b>" +
				    esc(String.valueOf(t.end())) + "</b>, the stack is cleared, the exception is pushed onto it, and execution continues at <b>" +
				    esc(String.valueOf(t.handler())) + "</b>.");
				if(t.type() == null) e.p("Catching anything is how <code>finally</code> blocks (and the cleanup of try-with-resources and " +
				                         "<code>synchronized</code>) are compiled: the handler runs the cleanup and throws the exception again.");
				e.p("When several entries cover the same instruction, the first matching one in this list wins.");
			}
			case MaxsInfo m -> {
				e.p("Not an instruction: the sizes the JVM reserves for each call of this method.");
				e.ul(List.of("<b>MAXSTACK = " + m.maxStack() + "</b>: the deepest the operand stack gets, in slots",
				             "<b>MAXLOCALS = " + m.maxLocals() + "</b>: the number of local variable slots, including <code>this</code> and the parameters"));
				e.p("The compiler works them out. Long and double values take 2 slots in both.");
			}
		}
		return e.out.toString();
	}

	/**
	 * Labels are not in the class file: instructions refer to code offsets, and ASM names them to print them.
	 */
	private void label(LabelInfo l){
		String name = String.valueOf(l.name());
		p("Label <b>" + esc(name) + "</b> names this position in the code so other lines can refer to it. It does not execute, " +
		  "and is not in the class file as such: the class file uses byte offsets, and ASM gives them names to print them.");
		List<String> uses = new ArrayList<>();
		for(var insn : l.method().insns){
			switch(insn.operand()){
				case JumpOperand j when name.equals(j.label()) -> uses.add("Target of <code>" + org.objectweb.asm.util.Printer.OPCODES[insn.opcode()] + "</code>");
				case SwitchOperand sw when name.equals(sw.defaultLabel()) || java.util.Arrays.asList(sw.labels()).contains(name) ->
					uses.add("Target of <code>" + org.objectweb.asm.util.Printer.OPCODES[insn.opcode()] + "</code>");
				case null, default -> { }
			}
		}
		for(var info : l.method().infos){
			switch(info){
				case LineInfo li when name.equals(li.label()) -> uses.add("Start of source line " + li.line());
				case VarInfo v when name.equals(v.start()) -> uses.add("Start of the scope of variable <b>" + esc(v.local().name()) + "</b>");
				case VarInfo v when name.equals(v.end()) -> uses.add("End of the scope of variable <b>" + esc(v.local().name()) + "</b>");
				case TryCatchInfo t when name.equals(t.handler()) -> uses.add("Start of an exception handler (" + (t.type() == null? "any exception" : className(t.type())) + ")");
				case TryCatchInfo t when name.equals(t.start()) -> uses.add("Start of a try block");
				case TryCatchInfo t when name.equals(t.end()) -> uses.add("End of a try block");
				default -> { }
			}
		}
		if(uses.isEmpty()) return;
		p("Used as:");
		ul(uses.stream().distinct().toList());
	}

	// ------------------------------------------------------------ frames

	/**
	 * Explains a stack map frame: what frames are for, what this one changes, and the complete state it describes.
	 */
	public static String explainFrame(Frame f){
		var e = new InsnExplainer(Resolver.NONE);
		e.frame(f);
		return e.out.toString();
	}

	private void frame(Frame f){
		p("Not an instruction: a <b>stack map frame</b>, which does nothing when the code runs. It states the types of the local variables " +
		  "and the operand stack at this point, because a jump or an exception handler lands here. The JVM's verifier checks the code against it " +
		  "in a single pass, instead of working out the types itself across every path that can get here.");
		String what = switch(f.kind()){
			case F_NEW -> "This frame lists everything (frames are expanded, so all of them are written out in full).";
			case F_FULL -> "<b>FULL</b> lists everything: the first list is the local variables in slot order, the second the operand stack.";
			case F_SAME -> "<b>SAME</b>: the same local variables as the previous frame, and an empty stack.";
			case F_SAME1 -> "<b>SAME1</b>: the same local variables as the previous frame, and exactly one value on the stack" +
			                (f.stack().size() == 1 && f.stack().getFirst() instanceof String? ", typically the caught exception at the start of a catch block." : ".");
			case F_APPEND -> "<b>APPEND</b>: the local variables of the previous frame, plus " + count(f.written().size(), "new one") +
			                 " (usually variables declared since), and an empty stack.";
			case F_CHOP -> "<b>CHOP " + f.chopped() + "</b>: the local variables of the previous frame without the last " + f.chopped() +
			               " (usually variables whose block has ended), and an empty stack.";
			default -> null;
		};
		if(what != null) p(what);
		if(f.kind() != F_FULL && f.kind() != F_NEW){
			p("Applied to the previous frame (or for the first frame, to the method's parameters), the complete state here is:");
		}

		if(f.locals().isEmpty()){
			p("<b>Local variables:</b> none.");
		}else{
			p("<b>Local variables:</b>");
			List<String> rows = new ArrayList<>();
			int slot = 0;
			for(int i = 0; i<f.locals().size(); i++){
				Object t    = f.locals().get(i);
				int    size = t == LONG || t == DOUBLE? 2 : 1;
				String name = f.localNames()[i];
				rows.add("slot " + (size == 2? slot + "\u2013" + (slot + 1) : slot) + ": " + frameType(t) + (name == null? "" : " <b>" + esc(name) + "</b>"));
				slot += size;
			}
			ul(rows);
		}
		if(f.stack().isEmpty()){
			p("<b>Operand stack:</b> empty.");
		}else{
			p("<b>Operand stack</b> (bottom first):");
			ul(f.stack().stream().map(InsnExplainer::frameType).toList());
		}
		if(f.locals().contains(TOP)){
			p("<code>T</code> (top) marks a slot with nothing usable in it: a variable whose block has ended, one that is not assigned on every path " +
			  "to here, or a slot after the last live variable. Code after this point cannot read it without storing to it first.");
		}
	}

	private static String frameType(Object t){
		if(t == TOP) return "<code>T</code> <span class=\"pkg\">unusable</span>";
		if(t == INTEGER) return "<code>I</code> <span class=\"pkg\">int, or boolean, byte, char, short</span>";
		if(t == FLOAT) return "<code>F</code> <span class=\"pkg\">float</span>";
		if(t == LONG) return "<code>J</code> <span class=\"pkg\">long, 2 slots</span>";
		if(t == DOUBLE) return "<code>D</code> <span class=\"pkg\">double, 2 slots</span>";
		if(t == NULL) return "<code>null</code> <span class=\"pkg\">known to be null, so any reference type</span>";
		if(t == UNINITIALIZED_THIS) return "<code>this</code>, before the superclass constructor has been called";
		if(t instanceof Uninitialized u){
			return (u.internalName() == null? "an object" : "a new " + className(u.internalName())) +
			       " created by <code>NEW</code> at <b>" + esc(u.label()) + "</b>, whose constructor has not run yet";
		}
		if(t instanceof String s) return s.startsWith("[")? type(Type.getType(s)) : className(s);
		return "<code>" + esc(String.valueOf(t)) + "</code>";
	}

	// ------------------------------------------------------------ locals

	private void variable(Insn insn, int op, VarOperand v){
		var    local = describeLocal(insn.method(), v);
		String type  = local.type != null? local.type : switch(op){
			case ILOAD, ISTORE, IINC -> "int";
			case LLOAD, LSTORE -> "long";
			case FLOAD, FSTORE -> "float";
			case DLOAD, DSTORE -> "double";
			case RET -> "returnAddress";
			default -> "reference";
		};
		int    size  = op == LLOAD || op == DLOAD || op == LSTORE || op == DSTORE? 2 : 1;
		String what  = local.label + " (" + type + ")";
		String where = " slot " + v.slot() + " (" + size + (size == 1? " slot)" : " slots)");
		switch(op){
			case ILOAD, LLOAD, FLOAD, DLOAD, ALOAD -> p("Loads " + what + " from" + where + " onto the stack.");
			case ISTORE, LSTORE, FSTORE, DSTORE, ASTORE -> p("Pops the top of the stack and stores it in " + what + " at" + where + ".");
			case IINC -> p((v.increment()<0? "Subtracts " + -v.increment() + " from " : "Adds " + v.increment() + " to ") + what +
			               " in slot " + v.slot() + ". The stack is not touched.");
			case RET -> p("Continues at the return address stored in slot " + v.slot() + ". Only found in old subroutine-based finally blocks.");
			default -> { }
		}
	}

	private record Local(String label, @Nullable String type){ }

	/**
	 * Names a local from the debug info. Without it, "this" and parameters can still be told apart by their slot.
	 */
	private static Local describeLocal(Method method, VarOperand v){
		var lv = v.local();
		if(lv != null){
			String type = lv.signature() != null? signatureType(lv.signature()) : type(Type.getType(lv.descriptor()));
			return new Local("local variable <b>\"" + esc(lv.name()) + "\"</b>", type);
		}
		boolean isStatic = (method.access & ACC_STATIC) != 0;
		int     slot     = isStatic? 0 : 1;
		if(!isStatic && v.slot() == 0) return new Local("<b>this</b>", className(method.owner));
		Type[] args = Type.getArgumentTypes(method.descriptor);
		for(int i = 0; i<args.length; i++){
			if(slot == v.slot()) return new Local("parameter " + (i + 1), type(args[i]));
			slot += args[i].getSize();
		}
		return new Local("local variable", null);
	}

	// ------------------------------------------------------------ constants

	private void intOperand(int op, int value){
		switch(op){
			case BIPUSH -> p("Pushes the int <b>" + value + "</b> (from a byte operand, sign extended).");
			case SIPUSH -> p("Pushes the int <b>" + value + "</b> (from a short operand, sign extended).");
			case NEWARRAY -> {
				String type = switch(value){
					case T_BOOLEAN -> "boolean";
					case T_CHAR -> "char";
					case T_FLOAT -> "float";
					case T_DOUBLE -> "double";
					case T_BYTE -> "byte";
					case T_SHORT -> "short";
					case T_INT -> "int";
					case T_LONG -> "long";
					default -> "?";
				};
				p("Pops the length and pushes a new <code>" + type + "[]</code> of that length, filled with zeros.");
			}
			default -> { }
		}
	}

	private void ldc(Object value){
		switch(value){
			case String s -> p("Pushes the String constant <b>\"" + esc(StringUtil.escapeStringCharacters(StringUtil.shortenTextWithEllipsis(s, 200, 20))) + "\"</b>.");
			case Integer i -> p("Pushes the int <b>" + i + "</b> from the constant pool.");
			case Float f -> p("Pushes the float <b>" + f + "</b> from the constant pool.");
			case Long l -> p("Pushes the long <b>" + l + "</b> from the constant pool (2 slots).");
			case Double d -> p("Pushes the double <b>" + d + "</b> from the constant pool (2 slots).");
			case Type t when t.getSort() == Type.METHOD -> p("Pushes a <code>MethodType</code> for " + methodType(t) + ".");
			case Type t -> p("Pushes the <code>Class</code> object of " + type(t) + ".");
			case Handle h -> p("Pushes a <code>MethodHandle</code> to " + handle(h) + ".");
			case ConstantDynamic c -> {
				p("Pushes a dynamically computed constant <b>" + esc(c.getName()) + "</b> of type " + type(Type.getType(c.getDescriptor())) +
				  ", computed once by the bootstrap method " + handle(c.getBootstrapMethod()) + ".");
				var args = new Object[c.getBootstrapMethodArgumentCount()];
				for(int i = 0; i<args.length; i++) args[i] = c.getBootstrapMethodArgument(i);
				bootstrapArgs(args);
			}
			default -> p("Pushes the constant " + esc(String.valueOf(value)) + ".");
		}
	}

	private void noOperand(Insn insn, int op){
		switch(op){
			case IRETURN, LRETURN, FRETURN, DRETURN, ARETURN -> {
				Type ret = Type.getReturnType(insn.method().descriptor);
				p("Returns " + type(ret) + " from <b>" + esc(insn.method().name) + "</b>: pops it and pushes it on the caller's stack.");
			}
			case RETURN -> {
				if(insn.method().name.equals("<clinit>")) p("Ends the static initializer.");
				else if(insn.method().name.equals("<init>")) p("Ends the constructor.");
				else p("Returns from <b>" + esc(insn.method().name) + "</b> without a value.");
			}
			default -> { }
		}
	}

	// ------------------------------------------------------------ fields

	private void field(int op, MemberOperand m){
		String fieldType = resolver.fieldType(m.owner(), m.name());
		Type   t         = Type.getType(m.descriptor());
		String typeText  = fieldType != null? "<code>" + esc(fieldType) + "</code>" : type(t);
		String name      = "<b>" + esc(m.name()) + "</b> (" + typeText + ")";
		String slots     = t.getSize() == 2? " (2 slots)" : "";
		switch(op){
			case GETSTATIC -> p("Reads static field " + name + " of " + className(m.owner()) + " and pushes its value" + slots + ".");
			case PUTSTATIC -> p("Pops a value" + slots + " and writes it to static field " + name + " of " + className(m.owner()) + ".");
			case GETFIELD -> p("Pops an object reference (" + className(m.owner()) + ") and pushes the value of its field " + name + slots + ".");
			case PUTFIELD -> p("Pops a value" + slots + " and an object reference (" + className(m.owner()) + "), and writes the value to the object's field " + name + ".");
			default -> { }
		}
	}

	// ------------------------------------------------------------ methods

	private void invoke(Insn insn, int op, MemberOperand m){
		boolean ctor  = m.name().equals("<init>");
		String  owner = className(m.owner());
		String  name  = "<b>" + esc(m.name()) + "</b>";
		var     info  = resolver.method(m.owner(), m.name(), m.descriptor());
		Type[]  args  = Type.getArgumentTypes(m.descriptor());

		String what = switch(op){
			case INVOKESTATIC -> "Calling static method " + name + " of " + owner;
			case INVOKEINTERFACE -> "Calling interface method " + name + " of " + owner;
			case INVOKEVIRTUAL -> "Calling method " + name + " of " + owner;
			default -> ctor? "Calling constructor of " + owner :
			           m.owner().equals(insn.method().owner)? "Calling private method " + name + " of " + owner :
			           "Calling " + name + " of " + owner + " as a <code>super</code> call, without virtual dispatch,";
		};
		p(what + (args.length == 0? " with no arguments." : " with " + count(args.length, "argument") + ":"));
		arguments(args, info);

		if(op != INVOKESTATIC){
			if(ctor){
				p(insn.method().name.equals("<init>")?
				  "Pops the arguments and the object to initialize: a new " + owner + " from <code>NEW</code>, or <code>this</code> when one constructor calls another or the superclass constructor." :
				  "Pops the arguments and the uninitialized object created by <code>NEW</code>, which can be used once this returns.");
			}else{
				p("Pops the arguments and the " + owner + " to call it on" + (op == INVOKESPECIAL? "." : ", and calls the implementation of the object's actual class."));
			}
		}
		if(!ctor) returnValue(Type.getReturnType(m.descriptor()), info);
		if(info != null && !info.exceptions().isEmpty()) list("Declares that it throws:", info.exceptions().stream().map(e -> "<code>" + esc(e) + "</code>").toList());
	}

	private void arguments(Type[] args, @Nullable MethodInfo info){
		if(args.length == 0) return;
		List<String> items = new ArrayList<>();
		for(int i = 0; i<args.length; i++){
			String type = info != null && info.parameterTypes() != null && i<info.parameterTypes().size()?
			              "<code>" + esc(info.parameterTypes().get(i)) + "</code>" : type(args[i]);
			String pname = info != null && i<info.parameterNames().size()? info.parameterNames().get(i) : null;
			items.add(type + (pname == null? "" : " " + esc(pname)));
		}
		ol(items);
	}

	private void returnValue(Type ret, @Nullable MethodInfo info){
		if(ret.getSort() == Type.VOID){
			p("Returns nothing.");
			return;
		}
		String type = info != null && info.returnType() != null? "<code>" + esc(info.returnType()) + "</code>" : type(ret);
		p("Returns " + type + ", pushed onto the stack" + (ret.getSize() == 2? " (2 slots)." : "."));
	}

	// ------------------------------------------------------------ invokedynamic

	private void indy(Insn insn, IndyOperand d){
		Handle bsm  = d.bootstrap();
		String key  = bsm.getOwner() + "." + bsm.getName();
		Object[] a  = d.arguments();
		Type[] captured = Type.getArgumentTypes(d.descriptor());
		switch(key){
			case "java/lang/invoke/LambdaMetafactory.metafactory", "java/lang/invoke/LambdaMetafactory.altMetafactory" -> {
				if(a.length<3 || !(a[0] instanceof Type sam) || !(a[1] instanceof Handle impl) || !(a[2] instanceof Type instantiated)){
					genericIndy(d);
					return;
				}
				String iface = type(Type.getReturnType(d.descriptor()));
				boolean lambda = impl.getName().startsWith("lambda$");
				p((lambda? "Creates a lambda" : "Creates a method reference") + " implementing " + iface +
				  ", whose method is <b>" + esc(d.name()) + "</b> " + methodType(instantiated) + ".");
				if(lambda){
					p("Its body was compiled into the synthetic method <b>" + esc(impl.getName()) + "</b>" + methodType(Type.getMethodType(impl.getDesc())) + " of this class.");
				}else{
					p("It calls " + handle(impl) + ".");
				}
				if(!sam.equals(instantiated)) p("The erased interface method is " + methodType(sam) + "; the values are cast to the types above.");
				if(captured.length == 0){
					p("Captures nothing, so the same instance is usually reused for every execution.");
				}else{
					p("Pops and captures " + count(captured.length, "value") + ":");
					ol(capturedNames(insn, impl, captured));
				}
				if(key.endsWith("altMetafactory") && a.length>3 && a[3] instanceof Integer flags){
					List<String> extras = new ArrayList<>();
					if((flags & 1) != 0) extras.add("serializable");
					if((flags & 2) != 0) extras.add("implements extra marker interfaces");
					if((flags & 4) != 0) extras.add("has bridge methods");
					if(!extras.isEmpty()) p("The created object " + String.join(", ", extras) + ".");
				}
				p("Pushes the " + iface + " instance. The class is spun up by <code>LambdaMetafactory</code> the first time this runs.");
			}
			case "java/lang/invoke/StringConcatFactory.makeConcatWithConstants", "java/lang/invoke/StringConcatFactory.makeConcat" -> {
				p("Concatenates " + count(captured.length, "value") + " popped from the stack into a String, like <code>+</code> does:");
				p("<code>" + concatRecipe(key.endsWith("WithConstants") && a.length>0 && a[0] instanceof String r? r : null, captured, a) + "</code>");
				p("Pushes the resulting String.");
			}
			case "java/lang/runtime/ObjectMethods.bootstrap" -> {
				String record = a.length>0 && a[0] instanceof Type t? type(t) : "the record";
				String names = a.length>1 && a[1] instanceof String s && !s.isEmpty()? s.replace(";", ", ") : "no components";
				p("The generated <b>" + esc(d.name()) + "</b> of record " + record + ", based on its components: " + esc(names) + ".");
			}
			case "java/lang/runtime/SwitchBootstraps.typeSwitch", "java/lang/runtime/SwitchBootstraps.enumSwitch" -> {
				p("Pops the value being switched on and a restart index, and pushes the index of the first case that matches, -1 for null, or the case count if none match:");
				List<String> cases = new ArrayList<>();
				for(Object o : a) cases.add(constant(o));
				ol0(cases);
				p("The <code>TABLESWITCH</code>/<code>LOOKUPSWITCH</code> after it jumps on that index.");
			}
			default -> genericIndy(d);
		}
	}

	/**
	 * Captured values are the first parameters of a lambda body, so its debug info knows their names.
	 */
	private static List<String> capturedNames(Insn insn, Handle impl, Type[] captured){
		Method body = null;
		if(impl.getOwner().equals(insn.method().owner)){
			for(var m : insn.method().siblings()){
				if(m.name.equals(impl.getName()) && m.descriptor.equals(impl.getDesc())) body = m;
			}
		}
		List<String> items = new ArrayList<>();
		boolean isStatic = body == null || (body.access & ACC_STATIC) != 0;
		int     slot     = 0;
		for(int i = 0; i<captured.length; i++){
			String name = null;
			if(body != null){
				if(i == 0 && !isStatic) name = "this";
				else for(var lv : body.locals){
					if(lv.slot() == slot){
						name = lv.name();
						break;
					}
				}
			}
			slot += captured[i].getSize();
			items.add(type(captured[i]) + (name == null? "" : " " + esc(name)));
		}
		return items;
	}

	private static final char ARG = '\u0001', CONST = '\u0002';

	private static String concatRecipe(@Nullable String recipe, Type[] args, Object[] bsmArgs){
		List<String> parts = new ArrayList<>();
		if(recipe == null){
			for(Type t : args) parts.add("‹" + plain(t) + "›");
			return String.join(" + ", parts);
		}
		var literal = new StringBuilder();
		int arg = 0, constant = 1;
		for(char c : recipe.toCharArray()){
			if(c == ARG || c == CONST){
				if(!literal.isEmpty()) parts.add("\"" + StringUtil.escapeStringCharacters(literal.toString()) + "\"");
				literal.setLength(0);
				if(c == ARG) parts.add("‹" + (arg<args.length? plain(args[arg++]) : "?") + "›");
				else parts.add(constant<bsmArgs.length? "\"" + StringUtil.escapeStringCharacters(String.valueOf(bsmArgs[constant++])) + "\"" : "?");
			}else{
				literal.append(c);
			}
		}
		if(!literal.isEmpty()) parts.add("\"" + StringUtil.escapeStringCharacters(literal.toString()) + "\"");
		return esc(String.join(" + ", parts));
	}

	private void genericIndy(IndyOperand d){
		Type[] args = Type.getArgumentTypes(d.descriptor());
		p("A dynamic call site named <b>" + esc(d.name()) + "</b>. The first time it runs, the bootstrap method " + handle(d.bootstrap()) +
		  " decides what it does; after that it is called directly.");
		if(args.length>0){
			p("Pops " + count(args.length, "argument") + ":");
			ol(java.util.Arrays.stream(args).map(InsnExplainer::type).toList());
		}
		Type ret = Type.getReturnType(d.descriptor());
		if(ret.getSort() != Type.VOID) p("Pushes " + type(ret) + ".");
		bootstrapArgs(d.arguments());
	}

	private void bootstrapArgs(Object[] args){
		if(args.length == 0) return;
		list("Static bootstrap arguments:", java.util.Arrays.stream(args).map(InsnExplainer::constant).toList());
	}

	// ------------------------------------------------------------ control flow

	private void jump(int op, String label){
		String target = "<b>" + esc(String.valueOf(label)) + "</b>";
		String cond = switch(op){
			case IFEQ -> "the popped int is 0 (false)";
			case IFNE -> "the popped int is not 0 (true)";
			case IFLT -> "the popped int is less than 0";
			case IFGE -> "the popped int is greater than or equal to 0";
			case IFGT -> "the popped int is greater than 0";
			case IFLE -> "the popped int is less than or equal to 0";
			case IF_ICMPEQ -> "the two popped ints are equal";
			case IF_ICMPNE -> "the two popped ints are not equal";
			case IF_ICMPLT -> "the lower int is less than the top one";
			case IF_ICMPGE -> "the lower int is greater than or equal to the top one";
			case IF_ICMPGT -> "the lower int is greater than the top one";
			case IF_ICMPLE -> "the lower int is less than or equal to the top one";
			case IF_ACMPEQ -> "the two popped references are the same object";
			case IF_ACMPNE -> "the two popped references are different objects";
			case IFNULL -> "the popped reference is null";
			case IFNONNULL -> "the popped reference is not null";
			default -> null;
		};
		if(op == GOTO) p("Always continues at " + target + ".");
		else if(op == JSR) p("Pushes the return address and jumps to the subroutine at " + target + ". Only found in old subroutine-based finally blocks.");
		else if(cond != null) p("Continues at " + target + " if " + cond + ", otherwise with the next instruction.");
	}

	private void switchInsn(SwitchOperand s){
		p("Pops an int and continues at the label of the matching case:");
		List<String> items = new ArrayList<>();
		for(int i = 0; i<s.keys().length; i++){
			// TABLESWITCH fills the gaps of its range with the default
			if(s.labels()[i] != null && s.labels()[i].equals(s.defaultLabel())) continue;
			items.add(s.keys()[i] + " → <b>" + esc(String.valueOf(s.labels()[i])) + "</b>");
		}
		items.add("anything else → <b>" + esc(String.valueOf(s.defaultLabel())) + "</b>");
		ul(items);
	}

	// ------------------------------------------------------------ types

	private void typeInsn(int op, String internalName){
		Type t = internalName.startsWith("[")? Type.getType(internalName) : Type.getObjectType(internalName);
		switch(op){
			case NEW -> p("Allocates a new " + type(t) + " and pushes a reference to it. It cannot be used until a constructor " +
			              "(<code>INVOKESPECIAL &lt;init&gt;</code>) has run on it, which is why a <code>DUP</code> usually follows: one copy for the constructor, one to keep.");
			case ANEWARRAY -> p("Pops the length and pushes a new " + type(Type.getType("[" + t.getDescriptor())) + " of that length, filled with nulls.");
			case CHECKCAST -> p("Checks that the reference on top of the stack is null or " + article(internalName) + " " + type(t) + ", and throws <code>ClassCastException</code> if not. The stack is unchanged.");
			case INSTANCEOF -> p("Pops a reference and pushes 1 if it is " + article(internalName) + " " + type(t) + ", or 0 if it is not or is null.");
			default -> { }
		}
	}

	/**
	 * A readable type: simple name in code font, followed by its package if it is not java.lang.
	 */
	static String type(Type t){
		return switch(t.getSort()){
			case Type.ARRAY -> {
				String elem = type(t.getElementType());
				yield elem.replaceFirst("</code>", "[]".repeat(t.getDimensions()) + "</code>");
			}
			case Type.OBJECT -> className(t.getInternalName());
			case Type.METHOD -> methodType(t);
			default -> "<code>" + t.getClassName() + "</code>";
		};
	}

	/** "a" or "an" for the simple name of a type, going by its first letter */
	private static String article(String internalName){
		String simple = internalName.substring(internalName.lastIndexOf('/') + 1).replaceAll("^[\\[L]*", "");
		return !simple.isEmpty() && "AEIOUaeiou".indexOf(simple.charAt(0))>=0? "an" : "a";
	}

	static String className(String internalName){
		if(internalName.startsWith("[")) return type(Type.getType(internalName));
		int    slash  = internalName.lastIndexOf('/');
		String simple = internalName.substring(slash + 1).replace('$', '.');
		String pkg    = slash<0? "" : internalName.substring(0, slash).replace('/', '.');
		return "<code>" + esc(simple) + "</code>" + (pkg.isEmpty() || pkg.equals("java.lang")? "" : " <span class=\"pkg\">" + esc(pkg) + "</span>");
	}

	/** Type without markup, for inside code */
	private static String plain(Type t){
		return switch(t.getSort()){
			case Type.ARRAY -> plain(t.getElementType()) + "[]".repeat(t.getDimensions());
			case Type.OBJECT -> t.getInternalName().substring(t.getInternalName().lastIndexOf('/') + 1).replace('$', '.');
			default -> t.getClassName();
		};
	}

	private static String methodType(Type m){
		var args = new ArrayList<String>();
		for(Type a : m.getArgumentTypes()) args.add(plain(a));
		return "<code>(" + esc(String.join(", ", args)) + ") → " + esc(plain(m.getReturnType())) + "</code>";
	}

	private static String handle(Handle h){
		String owner = className(h.getOwner());
		String name  = esc(h.getName());
		return switch(h.getTag()){
			case H_GETFIELD -> "the getter of field <b>" + name + "</b> of " + owner;
			case H_GETSTATIC -> "the getter of static field <b>" + name + "</b> of " + owner;
			case H_PUTFIELD -> "the setter of field <b>" + name + "</b> of " + owner;
			case H_PUTSTATIC -> "the setter of static field <b>" + name + "</b> of " + owner;
			case H_NEWINVOKESPECIAL -> "the constructor " + owner + methodType(Type.getMethodType(h.getDesc())) + " (<code>" + esc(plain(Type.getObjectType(h.getOwner()))) + "::new</code>)";
			case H_INVOKESTATIC -> "static method <b>" + name + "</b>" + methodType(Type.getMethodType(h.getDesc())) + " of " + owner;
			default -> "method <b>" + name + "</b>" + methodType(Type.getMethodType(h.getDesc())) + " of " + owner;
		};
	}

	private static String constant(Object o){
		return switch(o){
			case String s -> "<code>\"" + esc(StringUtil.escapeStringCharacters(s)) + "\"</code>";
			case Type t -> type(t);
			case Handle h -> handle(h);
			case ConstantDynamic c -> "the dynamic constant <b>" + esc(c.getName()) + "</b> " + type(Type.getType(c.getDescriptor()));
			default -> "<code>" + esc(String.valueOf(o)) + "</code>";
		};
	}

	private static final Pattern QUALIFIED = Pattern.compile("\\b(?:[a-z_$][\\w$]*\\.)+([A-Z][\\w$]*)");

	/** A generic type signature as Java source, with packages left out */
	static String signatureType(String signature){
		try{
			var v = new TraceSignatureVisitor(0);
			new SignatureReader(signature).acceptType(v);
			return "<code>" + esc(QUALIFIED.matcher(v.getDeclaration()).replaceAll("$1").replace('$', '.')) + "</code>";
		}catch(RuntimeException e){
			return "<code>" + esc(signature) + "</code>";
		}
	}

	// ------------------------------------------------------------ html

	private void p(String html){
		out.append("<p>").append(html).append("</p>");
	}

	private void ol(List<String> items){
		out.append("<ol>");
		for(var i : items) out.append("<li>").append(i).append("</li>");
		out.append("</ol>");
	}

	/** Numbered from 0, as case indexes are */
	private void ol0(List<String> items){
		out.append("<ul>");
		for(int i = 0; i<items.size(); i++) out.append("<li>").append(i).append(": ").append(items.get(i)).append("</li>");
		out.append("</ul>");
	}

	private void ul(List<String> items){
		out.append("<ul>");
		for(var i : items) out.append("<li>").append(i).append("</li>");
		out.append("</ul>");
	}

	private void list(String title, List<String> items){
		p(title);
		ul(items);
	}

	private static String count(int n, String what){
		return n + " " + what + (n == 1? "" : "s");
	}

	static String esc(String s){
		return StringUtil.escapeXmlEntities(s);
	}
}
