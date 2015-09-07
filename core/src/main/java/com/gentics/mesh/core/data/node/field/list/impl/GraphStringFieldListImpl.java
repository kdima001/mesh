package com.gentics.mesh.core.data.node.field.list.impl;

import com.gentics.mesh.core.data.node.field.basic.StringGraphField;
import com.gentics.mesh.core.data.node.field.impl.basic.StringGraphFieldImpl;
import com.gentics.mesh.core.data.node.field.list.AbstractBasicGraphFieldList;
import com.gentics.mesh.core.data.node.field.list.GraphStringFieldList;
import com.gentics.mesh.core.rest.node.field.list.impl.StringFieldListImpl;
import com.gentics.mesh.handler.ActionContext;

public class GraphStringFieldListImpl extends AbstractBasicGraphFieldList<StringGraphField, StringFieldListImpl>implements GraphStringFieldList {

	@Override
	public StringGraphField createString(String string) {
		StringGraphField field = createField();
		field.setString(string);
		return field;
	}

	@Override
	public StringGraphField getString(int index) {
		return getField(index);
	}

	@Override
	protected StringGraphField createField(String key) {
		return new StringGraphFieldImpl(key, getImpl());
	}

	@Override
	public Class<? extends StringGraphField> getListType() {
		return StringGraphFieldImpl.class;
	}

	@Override
	public void delete() {
		// TODO Auto-generated method stub
	}

	@Override
	public StringFieldListImpl transformToRest(ActionContext ac, String fieldKey) {
		StringFieldListImpl restModel = new StringFieldListImpl();
		for (StringGraphField item : getList()) {
			restModel.add(item.getString());
		}
		return restModel;
	}

}
