package org.objectweb.asm.idea.docs;

import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.ClassUtil;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Looks members up in the project and its libraries. Must be used in a read action.
 */
final class PsiResolver implements InsnExplainer.Resolver{
	
	/**
	 * Names made up by decompilers and stubs, not worth showing
	 */
	private static final Pattern MADE_UP = Pattern.compile("(?:arg|var|param|p)\\d+");
	
	private final Project project;
	
	PsiResolver(Project project){
		this.project = project;
	}
	
	private @Nullable PsiClass findClass(String internalName){
		var facade = JavaPsiFacade.getInstance(project);
		var scope  = GlobalSearchScope.allScope(project);
		// Nested classes: try every $ as a nesting separator first, it is by far the common case
		String   name = internalName.replace('/', '.');
		PsiClass c    = facade.findClass(name.replace('$', '.'), scope);
		return c != null? c : facade.findClass(name, scope);
	}
	
	@Override
	public @Nullable InsnExplainer.MethodInfo method(String owner, String name, String descriptor){
		var cls = findClass(owner);
		if(cls == null) return null;
		boolean     ctor       = name.equals("<init>");
		PsiMethod[] candidates = ctor? cls.getConstructors() : cls.findMethodsByName(name, true);
		for(PsiMethod m : candidates){
			if(!descriptor.equals(signature(m))) continue;
			List<String> names = new ArrayList<>(), types = new ArrayList<>();
			for(var p : m.getParameterList().getParameters()){
				names.add(MADE_UP.matcher(p.getName()).matches()? null : p.getName());
				types.add(p.getType().getPresentableText());
			}
			PsiType ret        = m.getReturnType();
			var     exceptions = Arrays.stream(m.getThrowsList().getReferencedTypes()).map(PsiType::getPresentableText).toList();
			// Inner class constructors and enum constructors have synthetic parameters javac adds
			if(types.size() != Type.getArgumentCount(descriptor))
				return new InsnExplainer.MethodInfo(List.of(), null, null, exceptions);
			return new InsnExplainer.MethodInfo(names, types, ret == null? null : ret.getPresentableText(), exceptions);
		}
		return null;
	}
	
	private static @Nullable String signature(PsiMethod m){
		try{
			return ClassUtil.getAsmMethodSignature(m);
		}catch(RuntimeException e){
			return null;
		}
	}
	
	@Override
	public @Nullable String fieldType(String owner, String name){
		var cls = findClass(owner);
		if(cls == null) return null;
		var field = cls.findFieldByName(name, true);
		return field == null? null : field.getType().getPresentableText();
	}
}
