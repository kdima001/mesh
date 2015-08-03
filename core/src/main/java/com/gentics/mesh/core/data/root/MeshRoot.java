package com.gentics.mesh.core.data.root;

import com.gentics.mesh.core.data.MeshVertex;
import com.gentics.mesh.core.data.root.impl.MeshRootImpl;
import com.gentics.mesh.core.data.search.SearchQueue;

/**
 * The mesh root is the primary graph element. All other aggregation nodes for users, roles, groups, projects connect to this element.
 */
public interface MeshRoot extends MeshVertex {

	static MeshRoot getInstance() {
		return MeshRootImpl.getInstance();
	}

	/**
	 * Returns the user aggregation vertex.
	 * 
	 * @return
	 */
	UserRoot getUserRoot();

	/**
	 * Returns the group aggregation vertex.
	 * 
	 * @return
	 */
	GroupRoot getGroupRoot();

	/**
	 * Returns the role aggregation vertex.
	 * 
	 * @return
	 */
	RoleRoot getRoleRoot();

	/**
	 * Returns the language aggregation vertex.
	 * 
	 * @return
	 */
	LanguageRoot getLanguageRoot();

	/**
	 * Returns the project aggregation vertex.
	 * 
	 * @return
	 */
	ProjectRoot getProjectRoot();

	/**
	 * Returns the tag aggregation vertex.
	 * 
	 * @return
	 */
	TagRoot getTagRoot();

	/**
	 * Returns the node aggregation vertex.
	 * 
	 * @return
	 */
	NodeRoot getNodeRoot();

	/**
	 * Returns the tag family aggregation vertex.
	 * 
	 * @return
	 */
	TagFamilyRoot getTagFamilyRoot();

	/**
	 * Returns the schema container aggregation vertex.
	 * 
	 * @return
	 */
	SchemaContainerRoot getSchemaContainerRoot();

	/**
	 * Returns the microschema container aggregation vertex.
	 * 
	 * @return
	 */
	MicroschemaContainerRoot getMicroschemaContainerRoot();

	/**
	 * Returns the search queue vertex.
	 * 
	 * @return
	 */
	SearchQueue getSearchQueue();

	/**
	 * Clear static references to the aggregation vertices.
	 */
	void clearReferences();
}
