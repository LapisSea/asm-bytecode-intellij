package org.objectweb.asm.idea.sync;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists the source constructs that compile to recognizable instructions, in the order javac evaluates them.
 * This is the source side of the alignment done by {@link InsnMapping}.
 */
public final class SourceAnchors{
	
	public enum Kind{
		INVOKE, INIT, NEW, LAMBDA, METHOD_REF, CONCAT,
		FIELD, VAR, CONST,
		BINOP, NEG, CONVERT, JUMP, SWITCH,
		CHECKCAST, INSTANCEOF,
		ARRAY_LOAD, ARRAY_STORE, ARRAY_LENGTH, NEW_ARRAY,
		RETURN, THROW, MONITOR
	}
	
	public enum Mode{READ, WRITE, ANY}
	
	/**
	 * @param name    method/field/variable name, or the operator for BINOP
	 * @param line    0-based line javac attributes the instruction to
	 * @param own     the text that is highlighted for this anchor, the smallest range that still identifies it
	 * @param element the source construct
	 */
	public record Anchor(Kind kind, String name, int argCount, int line, TextRange own, PsiElement element, Mode mode){ }
	
	private final Document     document;
	private final List<Anchor> out = new ArrayList<>();
	
	private SourceAnchors(Document document){
		this.document = document;
	}
	
	/**
	 * @param roots the code of one method, in order. For constructors this includes field initializers and initializer blocks.
	 */
	public static List<Anchor> collect(Document document, List<? extends PsiElement> roots){
		var a = new SourceAnchors(document);
		for(var root : roots) a.visit(root);
		return a.out;
	}
	
	private void add(Kind kind, @Nullable String name, int argCount, PsiElement lineElement, TextRange own, PsiElement element, Mode mode){
		if(own == null || own.isEmpty()) own = element.getTextRange();
		int offset = Math.min(lineElement.getTextRange().getStartOffset(), document.getTextLength());
		out.add(new Anchor(kind, name, argCount, document.getLineNumber(offset), own, element, mode));
	}
	
	private void add(Kind kind, @Nullable String name, PsiElement element){
		add(kind, name, -1, element, element.getTextRange(), element, Mode.ANY);
	}
	
	private void visitChildren(PsiElement e){
		for(PsiElement c = e.getFirstChild(); c != null; c = c.getNextSibling()) visit(c);
	}
	
	private void visit(@Nullable PsiElement e){
		if(e == null) return;
		switch(e){
			// Bodies compiled into other methods or classes
			case PsiClass ignored -> { }
			case PsiLambdaExpression l -> {
				PsiElement body = l.getBody();
				var        own  = body == null? l.getTextRange() : new TextRange(l.getTextRange().getStartOffset(), body.getTextRange().getStartOffset());
				add(Kind.LAMBDA, null, -1, l, own, l, Mode.ANY);
			}
			case PsiMethodReferenceExpression r -> {
				if(r.getQualifierExpression() != null && !(r.getQualifierExpression() instanceof PsiReferenceExpression q && q.resolve() instanceof PsiClass)){
					visit(r.getQualifierExpression());
				}
				add(Kind.METHOD_REF, r.isConstructor()? "<init>" : r.getReferenceName(), -1, r, r.getTextRange(), r, Mode.ANY);
			}
			case PsiMethodCallExpression c -> visitCall(c);
			case PsiNewExpression n -> visitNew(n);
			case PsiReferenceExpression r -> visitReference(r, Mode.READ);
			case PsiThisExpression t -> add(Kind.VAR, "this", -1, t, t.getTextRange(), t, Mode.READ);
			case PsiSuperExpression s -> add(Kind.VAR, "this", -1, s, s.getTextRange(), s, Mode.READ);
			case PsiLiteralExpression lit -> add(Kind.CONST, String.valueOf(lit.getValue()), -1, lit, lit.getTextRange(), lit, Mode.READ);
			case PsiAssignmentExpression a -> visitAssignment(a);
			case PsiUnaryExpression u -> visitUnary(u);
			case PsiPolyadicExpression p -> visitPolyadic(p);
			case PsiTypeCastExpression cast -> {
				visit(cast.getOperand());
				var type = cast.getCastType();
				var own = cast.getOperand() == null? cast.getTextRange() :
				          new TextRange(cast.getTextRange().getStartOffset(), cast.getOperand().getTextRange().getStartOffset());
				boolean primitive = type != null && type.getType() instanceof PsiPrimitiveType;
				add(primitive? Kind.CONVERT : Kind.CHECKCAST, type == null? null : type.getType().getPresentableText(), -1, cast, own, cast, Mode.ANY);
			}
			case PsiInstanceOfExpression i -> {
				visit(i.getOperand());
				var own = new TextRange(i.getOperand().getTextRange().getEndOffset(), i.getTextRange().getEndOffset());
				add(Kind.INSTANCEOF, null, -1, i, own, i, Mode.ANY);
				if(i.getPattern() != null) visitChildren(i.getPattern());
			}
			case PsiArrayAccessExpression a -> {
				visit(a.getArrayExpression());
				visit(a.getIndexExpression());
				add(Kind.ARRAY_LOAD, null, -1, a, bracketRange(a), a, Mode.READ);
			}
			case PsiConditionalExpression c -> {
				visit(c.getCondition());
				add(Kind.JUMP, null, -1, c, tokenRange(c, JavaTokenType.QUEST), c, Mode.ANY);
				visit(c.getThenExpression());
				visit(c.getElseExpression());
			}
			case PsiArrayInitializerExpression init -> {
				add(Kind.NEW_ARRAY, null, -1, init, init.getTextRange(), init, Mode.ANY);
				for(var el : init.getInitializers()){
					visit(el);
					add(Kind.ARRAY_STORE, null, -1, el, el.getTextRange(), el, Mode.WRITE);
				}
			}
			case PsiLocalVariable v -> {
				if(v.getInitializer() == null) return;
				visit(v.getInitializer());
				add(Kind.VAR, v.getName(), -1, v, v.getNameIdentifier() == null? v.getTextRange() : v.getNameIdentifier().getTextRange(), v, Mode.WRITE);
			}
			case PsiField f -> {
				// Field initializers are compiled into constructors/static initializers
				if(f.getInitializer() == null && !(f instanceof PsiEnumConstant)) return;
				if(f.hasModifierProperty(PsiModifier.STATIC) && f.hasModifierProperty(PsiModifier.FINAL) && f.computeConstantValue() != null) return;
				if(!f.hasModifierProperty(PsiModifier.STATIC)) add(Kind.VAR, "this", -1, f, nameRange(f), f, Mode.READ);
				if(f instanceof PsiEnumConstant ec){
					add(Kind.NEW, null, -1, ec, nameRange(ec), ec, Mode.ANY);
					if(ec.getArgumentList() != null) visit(ec.getArgumentList());
					add(Kind.INIT, "<init>", -1, ec, nameRange(ec), ec, Mode.ANY);
				}else{
					visit(f.getInitializer());
				}
				add(Kind.FIELD, f.getName(), -1, f, nameRange(f), f, Mode.WRITE);
			}
			case PsiReturnStatement r -> {
				visit(r.getReturnValue());
				add(Kind.RETURN, null, -1, r, r.getFirstChild().getTextRange(), r, Mode.ANY);
			}
			case PsiThrowStatement t -> {
				visit(t.getException());
				add(Kind.THROW, null, -1, t, t.getFirstChild().getTextRange(), t, Mode.ANY);
			}
			case PsiIfStatement s -> {
				visit(s.getCondition());
				add(Kind.JUMP, null, -1, s, s.getFirstChild().getTextRange(), s, Mode.ANY);
				visit(s.getThenBranch());
				visit(s.getElseBranch());
			}
			case PsiWhileStatement s -> {
				visit(s.getCondition());
				add(Kind.JUMP, null, -1, s, s.getFirstChild().getTextRange(), s, Mode.ANY);
				visit(s.getBody());
			}
			case PsiDoWhileStatement s -> {
				visit(s.getBody());
				visit(s.getCondition());
				add(Kind.JUMP, null, -1, s, s.getFirstChild().getTextRange(), s, Mode.ANY);
			}
			case PsiForStatement s -> {
				visit(s.getInitialization());
				visit(s.getCondition());
				add(Kind.JUMP, null, -1, s, s.getFirstChild().getTextRange(), s, Mode.ANY);
				visit(s.getBody());
				visit(s.getUpdate());
			}
			case PsiForeachStatement s -> visitForeach(s);
			case PsiSwitchBlock s -> {
				visit(s.getExpression());
				add(Kind.SWITCH, null, -1, s, s.getFirstChild().getTextRange(), s, Mode.ANY);
				visit(s.getBody());
			}
			case PsiSynchronizedStatement s -> {
				visit(s.getLockExpression());
				add(Kind.MONITOR, null, -1, s, s.getFirstChild().getTextRange(), s, Mode.ANY);
				visit(s.getBody());
			}
			case PsiCatchSection c -> {
				var p = c.getParameter();
				if(p != null) add(Kind.VAR, p.getName(), -1, p, nameRange(p), p, Mode.WRITE);
				visit(c.getCatchBlock());
			}
			case PsiComment ignored -> { }
			case PsiWhiteSpace ignored -> { }
			default -> visitChildren(e);
		}
	}
	
	private void visitCall(PsiMethodCallExpression c){
		var ref  = c.getMethodExpression();
		var name = ref.getReferenceName();
		var args = c.getArgumentList();
		var own = ref.getReferenceNameElement() == null? c.getTextRange() :
		          new TextRange(ref.getReferenceNameElement().getTextRange().getStartOffset(), c.getTextRange().getEndOffset());
		
		if("super".equals(name) || "this".equals(name)){
			add(Kind.VAR, "this", -1, c, own, c, Mode.READ);
			visit(args);
			add(Kind.INIT, "<init>", args.getExpressionCount(), args, own, c, Mode.ANY);
			return;
		}
		
		var qualifier = ref.getQualifierExpression();
		if(qualifier != null){
			visit(qualifier);
		}else{
			var method = c.resolveMethod();
			if(method != null && !method.hasModifierProperty(PsiModifier.STATIC)){
				// Implicit this
				add(Kind.VAR, "this", -1, c, own, c, Mode.READ);
			}
		}
		visit(args);
		// javac attributes the invoke to the line of the argument list
		add(Kind.INVOKE, name, args.getExpressionCount(), args, own, c, Mode.ANY);
	}
	
	private void visitNew(PsiNewExpression n){
		var own = n.getArgumentList() == null? n.getTextRange() :
		          new TextRange(n.getTextRange().getStartOffset(), n.getArgumentList().getTextRange().getEndOffset());
		if(n.isArrayCreation()){
			for(var dim : n.getArrayDimensions()) visit(dim);
			if(n.getArrayInitializer() != null){
				var init = n.getArrayInitializer();
				add(Kind.NEW_ARRAY, null, -1, n, new TextRange(n.getTextRange().getStartOffset(), init.getTextRange().getStartOffset()), n, Mode.ANY);
				for(var el : init.getInitializers()){
					visit(el);
					add(Kind.ARRAY_STORE, null, -1, el, el.getTextRange(), el, Mode.WRITE);
				}
			}else{
				add(Kind.NEW_ARRAY, null, -1, n, n.getTextRange(), n, Mode.ANY);
			}
			return;
		}
		var ref  = n.getClassOrAnonymousClassReference();
		var name = ref == null? null : ref.getReferenceName();
		add(Kind.NEW, name, -1, n, own, n, Mode.ANY);
		visit(n.getQualifier());
		var args = n.getArgumentList();
		if(args != null) visit(args);
		add(Kind.INIT, "<init>", args == null? -1 : args.getExpressionCount(), n, own, n, Mode.ANY);
	}
	
	private void visitReference(PsiReferenceExpression r, Mode mode){
		var nameElement = r.getReferenceNameElement();
		var own         = nameElement == null? r.getTextRange() : nameElement.getTextRange();
		var target      = r.resolve();
		switch(target){
			case PsiLocalVariable ignored -> add(Kind.VAR, r.getReferenceName(), -1, r, own, r, mode);
			case PsiParameter ignored -> add(Kind.VAR, r.getReferenceName(), -1, r, own, r, mode);
			case PsiField f -> {
				var     qualifier = r.getQualifierExpression();
				boolean constant  = mode == Mode.READ && f.hasModifierProperty(PsiModifier.FINAL) && f.computeConstantValue() != null;
				if(qualifier != null && !(qualifier instanceof PsiReferenceExpression q && q.resolve() instanceof PsiClass)){
					visit(qualifier);
				}else if(qualifier == null && !f.hasModifierProperty(PsiModifier.STATIC) && !constant){
					add(Kind.VAR, "this", -1, r, own, r, Mode.READ);
				}
				if(constant){
					add(Kind.CONST, String.valueOf(f.computeConstantValue()), -1, r, own, r, Mode.READ);
				}else if("length".equals(f.getName()) && qualifier != null && qualifier.getType() instanceof PsiArrayType){
					add(Kind.ARRAY_LENGTH, null, -1, r, own, r, Mode.READ);
				}else{
					add(Kind.FIELD, f.getName(), -1, r, own, r, mode);
				}
			}
			case null, default -> {
				// Class or package qualifiers do not generate code, but may contain expressions
				if(r.getQualifierExpression() != null && !(target instanceof PsiClass) && !(target instanceof PsiPackage)){
					visit(r.getQualifierExpression());
				}
			}
		}
	}
	
	private void visitAssignment(PsiAssignmentExpression a){
		var     lhs      = PsiUtil.skipParenthesizedExprDown(a.getLExpression());
		var     op       = a.getOperationTokenType();
		boolean compound = op != JavaTokenType.EQ;
		var     opRange  = a.getOperationSign().getTextRange();
		
		switch(lhs){
			case PsiArrayAccessExpression arr -> {
				visit(arr.getArrayExpression());
				visit(arr.getIndexExpression());
				if(compound) add(Kind.ARRAY_LOAD, null, -1, arr, bracketRange(arr), arr, Mode.READ);
				visit(a.getRExpression());
				if(compound) add(binopKind(a.getType()), opName(op), -1, a, opRange, a, Mode.ANY);
				add(Kind.ARRAY_STORE, null, -1, arr, bracketRange(arr), arr, Mode.WRITE);
			}
			case PsiReferenceExpression ref when ref.resolve() instanceof PsiField -> {
				var f   = (PsiField)ref.resolve();
				var own = ref.getReferenceNameElement() == null? ref.getTextRange() : ref.getReferenceNameElement().getTextRange();
				var q   = ref.getQualifierExpression();
				if(q != null && !(q instanceof PsiReferenceExpression qr && qr.resolve() instanceof PsiClass)) visit(q);
				else if(q == null && !f.hasModifierProperty(PsiModifier.STATIC)) add(Kind.VAR, "this", -1, ref, own, ref, Mode.READ);
				if(compound) add(Kind.FIELD, f.getName(), -1, ref, own, ref, Mode.READ);
				visit(a.getRExpression());
				if(compound) add(binopKind(a.getType()), opName(op), -1, a, opRange, a, Mode.ANY);
				add(Kind.FIELD, f.getName(), -1, ref, own, ref, Mode.WRITE);
			}
			case PsiReferenceExpression ref -> {
				var own = ref.getReferenceNameElement() == null? ref.getTextRange() : ref.getReferenceNameElement().getTextRange();
				if(compound) add(Kind.VAR, ref.getReferenceName(), -1, ref, own, ref, Mode.READ);
				visit(a.getRExpression());
				if(compound) add(binopKind(a.getType()), opName(op), -1, a, opRange, a, Mode.ANY);
				add(Kind.VAR, ref.getReferenceName(), -1, ref, own, ref, Mode.WRITE);
			}
			case null, default -> visitChildren(a);
		}
	}
	
	private void visitUnary(PsiUnaryExpression u){
		var op      = u.getOperationTokenType();
		var operand = PsiUtil.skipParenthesizedExprDown(u.getOperand());
		var opRange = u.getOperationSign().getTextRange();
		if(op == JavaTokenType.PLUSPLUS || op == JavaTokenType.MINUSMINUS){
			if(operand instanceof PsiReferenceExpression ref && ref.resolve() instanceof PsiField f){
				var own = ref.getReferenceNameElement() == null? ref.getTextRange() : ref.getReferenceNameElement().getTextRange();
				var q   = ref.getQualifierExpression();
				if(q != null && !(q instanceof PsiReferenceExpression qr && qr.resolve() instanceof PsiClass)) visit(q);
				else if(q == null && !f.hasModifierProperty(PsiModifier.STATIC)) add(Kind.VAR, "this", -1, ref, own, ref, Mode.READ);
				add(Kind.FIELD, f.getName(), -1, ref, own, ref, Mode.READ);
				add(Kind.BINOP, op == JavaTokenType.PLUSPLUS? "+" : "-", -1, u, opRange, u, Mode.ANY);
				add(Kind.FIELD, f.getName(), -1, ref, own, ref, Mode.WRITE);
			}else if(operand instanceof PsiReferenceExpression ref){
				// Usually a single IINC
				var own = new TextRange(Math.min(opRange.getStartOffset(), ref.getTextRange().getStartOffset()),
				                        Math.max(opRange.getEndOffset(), ref.getTextRange().getEndOffset()));
				add(Kind.VAR, ref.getReferenceName(), -1, u, own, u, Mode.ANY);
			}else{
				visitChildren(u);
			}
			return;
		}
		visit(operand);
		if(op == JavaTokenType.MINUS) add(Kind.NEG, "-", -1, u, opRange, u, Mode.ANY);
		else if(op == JavaTokenType.EXCL) add(Kind.JUMP, "!", -1, u, opRange, u, Mode.ANY);
		else if(op == JavaTokenType.TILDE) add(Kind.BINOP, "^", -1, u, opRange, u, Mode.ANY);
	}
	
	private void visitPolyadic(PsiPolyadicExpression p){
		var op       = p.getOperationTokenType();
		var operands = p.getOperands();
		if(op == JavaTokenType.PLUS && p.getType() != null && p.getType().equalsToText("java.lang.String")){
			for(var o : operands) visit(o);
			add(Kind.CONCAT, null, -1, p, p.getTextRange(), p, Mode.ANY);
			return;
		}
		boolean jump = op == JavaTokenType.ANDAND || op == JavaTokenType.OROR ||
		               op == JavaTokenType.EQEQ || op == JavaTokenType.NE ||
		               op == JavaTokenType.LT || op == JavaTokenType.GT || op == JavaTokenType.LE || op == JavaTokenType.GE;
		visit(operands[0]);
		for(int i = 1; i<operands.length; i++){
			if(op == JavaTokenType.ANDAND || op == JavaTokenType.OROR){
				var token = p.getTokenBeforeOperand(operands[i]);
				add(Kind.JUMP, opName(op), -1, p, token == null? p.getTextRange() : token.getTextRange(), p, Mode.ANY);
				visit(operands[i]);
				continue;
			}
			visit(operands[i]);
			var token = p.getTokenBeforeOperand(operands[i]);
			add(jump? Kind.JUMP : binopKind(p.getType()), opName(op), -1, p, token == null? p.getTextRange() : token.getTextRange(), p, Mode.ANY);
		}
	}
	
	private void visitForeach(PsiForeachStatement s){
		visit(s.getIteratedValue());
		var header = s.getRParenth() == null? s.getTextRange() :
		             new TextRange(s.getTextRange().getStartOffset(), s.getRParenth().getTextRange().getEndOffset());
		var type = s.getIteratedValue() == null? null : s.getIteratedValue().getType();
		if(!(type instanceof PsiArrayType)){
			add(Kind.INVOKE, "iterator", 0, s, header, s, Mode.ANY);
			add(Kind.INVOKE, "hasNext", 0, s, header, s, Mode.ANY);
			add(Kind.INVOKE, "next", 0, s, header, s, Mode.ANY);
		}else{
			add(Kind.ARRAY_LENGTH, null, -1, s, header, s, Mode.ANY);
			add(Kind.ARRAY_LOAD, null, -1, s, header, s, Mode.READ);
		}
		var p = s.getIterationParameter();
		add(Kind.VAR, p.getName(), -1, p, nameRange(p), p, Mode.WRITE);
		visit(s.getBody());
	}
	
	private static Kind binopKind(@Nullable PsiType type){
		return type != null && type.equalsToText("java.lang.String")? Kind.CONCAT : Kind.BINOP;
	}
	
	private static String opName(IElementType op){
		if(op == JavaTokenType.PLUS || op == JavaTokenType.PLUSEQ) return "+";
		if(op == JavaTokenType.MINUS || op == JavaTokenType.MINUSEQ) return "-";
		if(op == JavaTokenType.ASTERISK || op == JavaTokenType.ASTERISKEQ) return "*";
		if(op == JavaTokenType.DIV || op == JavaTokenType.DIVEQ) return "/";
		if(op == JavaTokenType.PERC || op == JavaTokenType.PERCEQ) return "%";
		if(op == JavaTokenType.AND || op == JavaTokenType.ANDEQ) return "&";
		if(op == JavaTokenType.OR || op == JavaTokenType.OREQ) return "|";
		if(op == JavaTokenType.XOR || op == JavaTokenType.XOREQ) return "^";
		if(op == JavaTokenType.LTLT || op == JavaTokenType.LTLTEQ) return "<<";
		if(op == JavaTokenType.GTGT || op == JavaTokenType.GTGTEQ) return ">>";
		if(op == JavaTokenType.GTGTGT || op == JavaTokenType.GTGTGTEQ) return ">>>";
		return op.toString();
	}
	
	private static TextRange nameRange(PsiNameIdentifierOwner owner){
		var id = owner.getNameIdentifier();
		return id == null? owner.getTextRange() : id.getTextRange();
	}
	
	private static TextRange bracketRange(PsiArrayAccessExpression a){
		return new TextRange(a.getArrayExpression().getTextRange().getEndOffset(), a.getTextRange().getEndOffset());
	}
	
	private static TextRange tokenRange(PsiElement parent, IElementType token){
		for(PsiElement c = parent.getFirstChild(); c != null; c = c.getNextSibling()){
			if(c instanceof PsiJavaToken t && t.getTokenType() == token) return t.getTextRange();
		}
		return parent.getTextRange();
	}
}
