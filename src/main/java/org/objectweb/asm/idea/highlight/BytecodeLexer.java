package org.objectweb.asm.idea.highlight;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Lexes Textifier output a line at a time. The state is 0 at line starts only, the places it can restart from.
 */
public final class BytecodeLexer extends LexerBase{

	private CharSequence buffer;
	private int          bufferEnd;

	private final List<IElementType> types  = new ArrayList<>();
	private       int[]              starts = new int[16];
	private       int[]              ends   = new int[16];
	private       int                index;
	private       int                lineStart;

	@Override
	public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState){
		this.buffer = buffer;
		this.bufferEnd = endOffset;
		nextLine(startOffset);
	}

	private void nextLine(int from){
		types.clear();
		index = 0;
		lineStart = from;
		while(types.isEmpty() && from<bufferEnd){
			lineStart = from;
			int nl = from;
			while(nl<bufferEnd && buffer.charAt(nl) != '\n') nl++;
			LineTokenizer.tokenize(buffer, from, nl, this::add);
			if(nl<bufferEnd){
				add(TokenType.WHITE_SPACE, nl, nl + 1);
				from = nl + 1;
			}else{
				from = nl;
			}
		}
	}

	private void add(IElementType type, int start, int end){
		int i = types.size();
		if(i == starts.length){
			starts = java.util.Arrays.copyOf(starts, i*2);
			ends = java.util.Arrays.copyOf(ends, i*2);
		}
		types.add(type);
		starts[i] = start;
		ends[i] = end;
	}

	@Override
	public int getState(){
		return index<types.size() && starts[index] == lineStart? 0 : 1;
	}

	@Override
	public @Nullable IElementType getTokenType(){
		return index<types.size()? types.get(index) : null;
	}

	@Override
	public int getTokenStart(){
		return index<types.size()? starts[index] : bufferEnd;
	}

	@Override
	public int getTokenEnd(){
		return index<types.size()? ends[index] : bufferEnd;
	}

	@Override
	public void advance(){
		if(index>=types.size()) return;
		int end = ends[index];
		index++;
		if(index == types.size()) nextLine(end);
	}

	@Override
	public @NotNull CharSequence getBufferSequence(){
		return buffer;
	}

	@Override
	public int getBufferEnd(){
		return bufferEnd;
	}
}
