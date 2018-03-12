/*******************************************************************************
 * Copyright (C) 2010, Dariusz Luksza <dariusz@luksza.org>
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.egit.ui.internal.synchronize.model;

import java.io.IOException;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.structuremergeviewer.Differencer;
import org.eclipse.compare.structuremergeviewer.ICompareInputChangeListener;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.egit.ui.UIText;
import org.eclipse.egit.ui.internal.CompareUtils;
import org.eclipse.egit.ui.internal.FileRevisionTypedElement;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.graphics.Image;
import org.eclipse.team.ui.mapping.ISynchronizationCompareInput;
import org.eclipse.team.ui.mapping.SaveableComparison;

/**
 * Git blob object representation in Git ChangeSet
 */
public class GitModelBlob extends GitModelCommit implements ISynchronizationCompareInput {

	private final String name;

	private final ObjectId baseId;

	private final ObjectId remoteId;

	private final ObjectId ancestorId;

	private final IPath location;

	private final String gitPath;

	private static final GitModelObject[] empty = new GitModelObject[0];

	/**
	 *
	 * @param parent
	 *            parent of this object
	 * @param commit
	 *            remote commit
	 * @param ancestorId
	 *            common ancestor id
	 * @param baseId
	 *            id of base object variant
	 * @param remoteId
	 *            id of remote object variant
	 * @param name
	 *            human readable blob name (file name)
	 * @throws IOException
	 */
	public GitModelBlob(GitModelObject parent, RevCommit commit,
			ObjectId ancestorId, ObjectId baseId, ObjectId remoteId, String name)
			throws IOException {
		super(parent, commit);
		this.name = name;
		this.baseId = baseId;
		this.remoteId = remoteId;
		this.ancestorId = ancestorId;
		location = getParent().getLocation().append(name);
		gitPath = Repository.stripWorkDir(getRepository().getWorkTree(),
				getLocation().toFile());
	}

	@Override
	public GitModelObject[] getChildren() {
		return empty;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public IProject[] getProjects() {
		return getParent().getProjects();
	}

	@Override
	public IPath getLocation() {
		return location;
	}

	public Image getImage() {
		// currently itsn't used
		return null;
	}

	public int getKind() {
		return Differencer.CONFLICTING;
	}

	public ITypedElement getAncestor() {
		return CompareUtils.getFileRevisionTypedElement(gitPath,
				getAncestorCommit(), getRepository(), ancestorId);
	}

	public ITypedElement getLeft() {
		if (getBaseCommit() != null && baseId != null)
			return CompareUtils.getFileRevisionTypedElement(gitPath,
					getBaseCommit(), getRepository(), baseId);

		return null;
	}

	public ITypedElement getRight() {
		return CompareUtils.getFileRevisionTypedElement(gitPath,
				getRemoteCommit(), getRepository(), remoteId);
	}

	public void addCompareInputChangeListener(
			ICompareInputChangeListener listener) {
		// data in commit will never change, therefore change listeners are
		// useless
	}

	public void removeCompareInputChangeListener(
			ICompareInputChangeListener listener) {
		// data in commit will never change, therefore change listeners are
		// useless
	}

	public void copy(boolean leftToRight) {
		// do nothing, we should disallow coping content between commits
	}

	public SaveableComparison getSaveable() {
		// TODO Auto-generated method stub
		return null;
	}

	public void prepareInput(CompareConfiguration configuration,
			IProgressMonitor monitor) throws CoreException {
		configuration.setLeftLabel(getFileRevisionLabel((FileRevisionTypedElement)getLeft()));
		configuration.setRightLabel(getFileRevisionLabel((FileRevisionTypedElement)getRight()));

	}

	private String getFileRevisionLabel(FileRevisionTypedElement element) {
		return NLS.bind(UIText.GitCompareFileRevisionEditorInput_RevisionLabel,
				new Object[]{element.getName(),
				CompareUtils.truncatedRevision(element.getContentIdentifier()), element.getAuthor()});
	}

	public String getFullPath() {
		// TODO Auto-generated method stub
		return null;
	}

	public boolean isCompareInputFor(Object object) {
		// TODO Auto-generated method stub
		return false;
	}

}
