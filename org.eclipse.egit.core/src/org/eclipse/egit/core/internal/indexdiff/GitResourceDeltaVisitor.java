/*******************************************************************************
 * Copyright (C) 2011, Dariusz Luksza <dariusz@luksza.org>
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *  * Jens Baumgart <jens.baumgart@sap.com> - initial implementation in IndexDifCacheEntry
 *  * Dariusz Luksza - extraction to separate class
 *******************************************************************************/
package org.eclipse.egit.core.internal.indexdiff;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.egit.core.Activator;
import org.eclipse.egit.core.project.RepositoryMapping;
import org.eclipse.jgit.lib.Repository;

/**
 * Git specific implementation of {@link IResourceDeltaVisitor} that ignores not
 * interesting resources. Also collects list of paths and resources to update
 */
public class GitResourceDeltaVisitor implements IResourceDeltaVisitor {

	private static final String GITIGNORE_NAME = ".gitignore"; //$NON-NLS-1$

	/**
	 * Bit-mask describing interesting changes for IResourceChangeListener
	 * events
	 */
	private static int INTERESTING_CHANGES = IResourceDelta.CONTENT
			| IResourceDelta.MOVED_FROM | IResourceDelta.MOVED_TO
			| IResourceDelta.OPEN | IResourceDelta.REPLACED
			| IResourceDelta.TYPE;

	private final Repository repository;

	private final Collection<String> filesToUpdate;

	private final Collection<IResource> resourcesToUpdate;

	private boolean gitIgnoreChanged = false;

	/**
	 * Constructs {@link GitResourceDeltaVisitor}
	 *
	 * @param repository
	 *            which should be considered during visiting
	 *            {@link IResourceDelta}s
	 */
	public GitResourceDeltaVisitor(Repository repository) {
		this.repository = repository;

		filesToUpdate = new HashSet<String>();
		resourcesToUpdate = new HashSet<IResource>();
	}

	public boolean visit(IResourceDelta delta) throws CoreException {
		final IResource resource = delta.getResource();
		// If the resource is not part of a project under
		// Git revision control
		final RepositoryMapping mapping = RepositoryMapping
				.getMapping(resource);

		if (mapping == null || mapping.getRepository() != repository)
			// Ignore the change
			return true;

		// used to lookup ignored resources in the last version of the
		// IndexDiffCacheEntry. This code is only used during updates to the
		// index, and only as long as no .gitignore changes (see .gitignore
		// handling below).
		IndexDiffCache cache = Activator.getDefault().getIndexDiffCache();
		IndexDiffCacheEntry entry = null;

		if (cache != null)
			entry = cache.getIndexDiffCacheEntry(mapping.getRepository());

		if (resource instanceof IFolder
				&& delta.getKind() == IResourceDelta.ADDED) {

			String repoRelativePath = mapping.getRepoRelativePath(resource)
					+ "/"; //$NON-NLS-1$

			if (isIgnoredInOldIndex(entry, repoRelativePath)) {
				return false; // the directory is ignored - stop recursion here.
			}

			filesToUpdate.add(repoRelativePath);
			resourcesToUpdate.add(resource);
			return true;
		}

		// If the file has changed but not in a way that we
		// care about (e.g. marker changes to files) then
		// ignore
		if (delta.getKind() == IResourceDelta.CHANGED
				&& (delta.getFlags() & INTERESTING_CHANGES) == 0)
			return true;

		// skip any non-FILE resources
		if (resource.getType() != IResource.FILE)
			return true;

		String repoRelativePath = mapping.getRepoRelativePath(resource);
		if (isIgnoredInOldIndex(entry, repoRelativePath)) {
			// ignore the change itself, and also all changes to children of
			// this resource (it's a file anyway). This even holds true for
			// .gitignore files, as otherwise .gitignore files copied to /bin/
			// directories by the Eclipse compiler will cause full re-indexing
			// of the repository during builds.
			return false;
		}

		if (resource.getName().equals(GITIGNORE_NAME)) {
			gitIgnoreChanged = true;
			return false;
		}

		if (repoRelativePath!= null)
			filesToUpdate.add(repoRelativePath);
		resourcesToUpdate.add(resource);

		return true;
	}

	/**
	 * @param entry
	 *            the {@link IndexDiffCacheEntry} for the repository containing
	 *            the path.
	 * @param path
	 *            the repository relative path of the resource to check
	 * @return whether the given path is ignored by the given
	 *         {@link IndexDiffCacheEntry}
	 */
	public boolean isIgnoredInOldIndex(IndexDiffCacheEntry entry, String path) {
		// fall back to processing all changes as long as there is no old index.
		if (entry == null)
			return false;

		IndexDiffData indexDiff = entry.getIndexDiff();
		if (indexDiff == null)
			return false;

		for (String s : indexDiff.getIgnoredNotInIndex()) {
			String sWithSlash = s;
			if (!s.endsWith("/")) { //$NON-NLS-1$
				sWithSlash = s + '/';
			}

			// Either paths are equal, or the path is a sub-path of the given
			// ignored entry to be ignored
			if (path.equals(s) || path.startsWith(sWithSlash)) {
				// ignored! skip all children!
				return true;
			}
		}
		return false;
	}

	/**
	 * @return collection of files to update
	 */
	public Collection<IFile> getFileResourcesToUpdate() {
		Collection<IFile> result = new ArrayList<IFile>();
		for (IResource resource : resourcesToUpdate)
			if (resource instanceof IFile)
				result.add((IFile) resource);
		return result;
	}

	/**
	 * @return collection of resources to update
	 */
	public Collection<IResource> getResourcesToUpdate() {
		return resourcesToUpdate;
	}

	/**
	 * @return collection of files / folders to update. Folder paths end with /
	 */
	public Collection<String> getFilesToUpdate() {
		return filesToUpdate;
	}

	/**
	 * @return {@code true} when content .gitignore file changed, {@code false}
	 *         otherwise
	 */
	public boolean getGitIgnoreChanged() {
		return gitIgnoreChanged;
	}
}