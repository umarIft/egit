/*******************************************************************************
 * Copyright (C) 2007, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org>
 * Copyright (c) 2010, Stefan Lay <stefan.lay@sap.com>
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.egit.ui.internal.history;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.MyersDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;

class FileDiff {

	private final RevCommit commit;

	private DiffEntry diffEntry;

	private static ObjectId[] trees(final RevCommit commit) {
		final ObjectId[] r = new ObjectId[commit.getParentCount() + 1];
		for (int i = 0; i < r.length - 1; i++)
			r[i] = commit.getParent(i).getTree().getId();
		r[r.length - 1] = commit.getTree().getId();
		return r;
	}

	static FileDiff[] compute(final TreeWalk walk, final RevCommit commit)
			throws MissingObjectException, IncorrectObjectTypeException,
			CorruptObjectException, IOException {
		final ArrayList<FileDiff> r = new ArrayList<FileDiff>();

		walk.reset(trees(commit));
		List<DiffEntry> entries = DiffEntry.scan(walk);

		for (DiffEntry entry : entries) {
			final FileDiff d = new FileDiff(commit, entry);
			r.add(d);
		}

		final FileDiff[] tmp = new FileDiff[r.size()];
		r.toArray(tmp);
		return tmp;
	}

	/**
	 * Creates a textual diff together with meta information.
	 * TODO So far this works only in case of one parent commit.
	 *
	 * @param d
	 *            the StringBuilder where the textual diff is added to
	 * @param db
	 *            the Repo
	 * @param diffFmt
	 *            the DiffFormatter used to create the textual diff
	 * @param gitFormat
	 *            if false, do not show any source or destination prefix,
	 *            and the paths are calculated relative to the eclipse
	 *            project, otherwise relative to the git repository
	 * @throws IOException
	 */
	public void outputDiff(final StringBuilder d, final Repository db,
			final DiffFormatter diffFmt, boolean gitFormat) throws IOException {
		if (gitFormat) {
			diffFmt.setRepository(db);
			diffFmt.format(diffEntry);
			return;
		}

		outputEclipseDiff(d, db, diffFmt);
	}

	private void outputEclipseDiff(final StringBuilder d, final Repository db,
			final DiffFormatter diffFmt) throws IOException {
		if (!(getBlobs().length == 2))
			throw new UnsupportedOperationException(
					"Not supported yet if the number of parents is different from one"); //$NON-NLS-1$

		String projectRelativePath = getProjectRelativePath(db, getPath());
		d.append("diff --git ").append(projectRelativePath).append(" ") //$NON-NLS-1$ //$NON-NLS-2$
			.append(projectRelativePath).append("\n"); //$NON-NLS-1$
		final ObjectId id1 = getBlobs()[0];
		final ObjectId id2 = getBlobs()[1];
		final FileMode mode1 = getModes()[0];
		final FileMode mode2 = getModes()[1];

		if (id1.equals(ObjectId.zeroId())) {
			d.append("new file mode " + mode2).append("\n"); //$NON-NLS-1$//$NON-NLS-2$
		} else if (id2.equals(ObjectId.zeroId())) {
			d.append("deleted file mode " + mode1).append("\n"); //$NON-NLS-1$//$NON-NLS-2$
		} else if (!mode1.equals(mode2)) {
			d.append("old mode " + mode1); //$NON-NLS-1$
			d.append("new mode " + mode2).append("\n"); //$NON-NLS-1$//$NON-NLS-2$
		}
		d.append("index ").append(id1.abbreviate(db, 7).name()). //$NON-NLS-1$
				append("..").append(id2.abbreviate(db, 7).name()). //$NON-NLS-1$
				append(mode1.equals(mode2) ? " " + mode1 : "").append("\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		if (id1.equals(ObjectId.zeroId()))
			d.append("--- /dev/null\n"); //$NON-NLS-1$
		else {
			d.append("--- "); //$NON-NLS-1$
			d.append(getProjectRelativePath(db, getPath()));
			d.append("\n"); //$NON-NLS-1$
		}

		if (id2.equals(ObjectId.zeroId()))
			d.append("+++ /dev/null\n"); //$NON-NLS-1$
		else {
			d.append("+++ "); //$NON-NLS-1$
			d.append(getProjectRelativePath(db, getPath()));
			d.append("\n"); //$NON-NLS-1$
		}

		final RawText a = getRawText(id1, db);
		final RawText b = getRawText(id2, db);
		final MyersDiff diff = new MyersDiff(a, b);
		diffFmt.formatEdits(a, b, diff.getEdits());
	}

	private String getProjectRelativePath(Repository db, String repoPath) {
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		IWorkspaceRoot root = workspace.getRoot();
		IPath absolutePath = new Path(db.getWorkTree().getAbsolutePath()).append(repoPath);
		IResource resource = root.getFileForLocation(absolutePath);
		return resource.getProjectRelativePath().toString();
	}

	private RawText getRawText(ObjectId id, Repository db) throws IOException {
		if (id.equals(ObjectId.zeroId()))
			return new RawText(new byte[] { });
		return new RawText(db.open(id).getCachedBytes());
	}


	public RevCommit getCommit() {
		return commit;
	}

	public String getPath() {
		if (ChangeType.DELETE.equals(diffEntry.getChangeType()))
			return diffEntry.getOldPath();
		return diffEntry.getNewPath();
	}

	public String getChange() {
		return diffEntry.getChangeType().name();
	}

	public ObjectId[] getBlobs() {
		List<ObjectId> objectIds = new ArrayList<ObjectId>();
		if (diffEntry.getOldId() != null)
			objectIds.add(diffEntry.getOldId().toObjectId());
		if (diffEntry.getNewId() != null)
			objectIds.add(diffEntry.getNewId().toObjectId());
		return objectIds.toArray(new ObjectId[]{});
	}

	public FileMode[] getModes() {
		List<FileMode> modes = new ArrayList<FileMode>();
		if (diffEntry.getOldMode() != null)
			modes.add(diffEntry.getOldMode());
		if (diffEntry.getOldMode() != null)
			modes.add(diffEntry.getOldMode());
		return modes.toArray(new FileMode[]{});
	}

	FileDiff(final RevCommit c, final DiffEntry entry) {
		diffEntry = entry;
		commit = c;
	}
}
