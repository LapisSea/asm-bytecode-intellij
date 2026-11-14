package org.objectweb.asm.idea.sync;

import java.util.List;

/**
 * Compiled by the build and also parsed as source by {@link InsnMappingTest}. Changing it changes the test.
 */
@SuppressWarnings("unused")
public class InsnMappingFixture{
	record Foo(String stuff){
		boolean something(){ return !stuff.isEmpty(); }
	}
	
	record Thing(Foo foo){ }
	
	private int counter = Integer.parseInt("1");
	
	static List<String> run(List<Thing> things){
		List<String> stuff = things.stream().map(Thing::foo).filter(f -> f.something()).map(Foo::stuff).toList();
		return stuff;
	}
	
	int sum(int a, int b){
		int c = a + b*counter;
		counter++;
		return c;
	}
}
