/*
 *
 *  Copyright 2011 Cédric Champeau
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * /
 */

package org.objectweb.asm.idea.config;

import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.EnumComboBoxModel;

import javax.swing.*;
import java.awt.Component;
import java.util.EnumMap;
import java.util.Objects;

public class ASMPluginConfiguration{
	protected static final String                     COMPONENT_NAME = "ASMPluginConfiguration";
	private                JPanel                     contentPane;
	private                JCheckBox                  skipDebugCheckBox;
	private                JCheckBox                  skipFramesCheckBox;
	private                JCheckBox                  skipCodeCheckBox;
	private                JCheckBox                  expandFramesCheckBox;
	private                JComboBox<GroovyCodeStyle> groovyCodeStyleComboBox;
	
	public ASMPluginConfiguration(){
	}
	
	public JComponent getRootPane(){
		return contentPane;
	}
	
	public void setData(ASMPluginComponent data){
		skipDebugCheckBox.setSelected(data.isSkipDebug());
		skipFramesCheckBox.setSelected(data.isSkipFrames());
		skipCodeCheckBox.setSelected(data.isSkipCode());
		expandFramesCheckBox.setSelected(data.isExpandFrames());
		groovyCodeStyleComboBox.setSelectedItem(data.getCodeStyle());
	}
	
	public void getData(ASMPluginComponent data){
		data.setSkipDebug(skipDebugCheckBox.isSelected());
		data.setSkipFrames(skipFramesCheckBox.isSelected());
		data.setSkipCode(skipCodeCheckBox.isSelected());
		data.setExpandFrames(expandFramesCheckBox.isSelected());
		data.setCodeStyle((GroovyCodeStyle)groovyCodeStyleComboBox.getSelectedItem());
	}
	
	public boolean isModified(ASMPluginComponent data){
		if(skipDebugCheckBox.isSelected() != data.isSkipDebug()) return true;
		if(skipFramesCheckBox.isSelected() != data.isSkipFrames()) return true;
		if(skipCodeCheckBox.isSelected() != data.isSkipCode()) return true;
		if(expandFramesCheckBox.isSelected() != data.isExpandFrames()) return true;
		return !Objects.equals(groovyCodeStyleComboBox.getSelectedItem(), data.getCodeStyle());
	}
	
	private void createUIComponents(){
		ComboBoxModel<GroovyCodeStyle> model = new EnumComboBoxModel<>(GroovyCodeStyle.class);
		groovyCodeStyleComboBox = new ComboBox<>(model);
		groovyCodeStyleComboBox.setRenderer(new GroovyCodeStyleCellRenderer());
	}
	
	private static final class GroovyCodeStyleCellRenderer implements ListCellRenderer<GroovyCodeStyle>{
		private final EnumMap<GroovyCodeStyle, JLabel> labels;
		
		private GroovyCodeStyleCellRenderer(){
			labels = new EnumMap<>(GroovyCodeStyle.class);
			for(GroovyCodeStyle codeStyle : GroovyCodeStyle.values()){
				labels.put(codeStyle, new JLabel(codeStyle.label));
			}
		}
		
		@Override
		public Component getListCellRendererComponent(final JList<? extends GroovyCodeStyle> list, final GroovyCodeStyle value, final int index, final boolean isSelected, final boolean cellHasFocus){
			return labels.get(value);
		}
	}
}
