/*******************************************************************************
 * Copyright (C) 2011, Dariusz Luksza <dariusz@luksza.org>
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.egit.core.synchronize;

import static org.eclipse.compare.structuremergeviewer.Differencer.LEFT;
import static org.eclipse.compare.structuremergeviewer.Differencer.RIGHT;
import static org.eclipse.jgit.lib.ObjectId.zeroId;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.compare.structuremergeviewer.Differencer;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.MutableObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevFlag;
import org.eclipse.jgit.revwalk.RevFlagSet;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

/**
 * Retrieves from git list of checked in commits and changes associated with it
 */
public class CheckedInCommitsCache {

	/**
	 * Corresponds to {@link RevCommit} object, but contains only those data
	 * that are required by Synchronize view Change Set
	 */
	public static class Commit {
		private int direction;

		private String shortMessage;

		private int commitTime;

		private AbbreviatedObjectId commitId;

		private Date commitDate;

		private String authorName;

		private String committerName;

		private Map<String, Change> children;

		private Commit() {
			// reduce the visibility of the default constructor
		}

		/**
		 * Indicates does this commit is incoming or outgoing. Returned value
		 * corresponds to {@link Differencer#LEFT} for incoming and
		 * {@link Differencer#RIGHT} for outgoing changes
		 *
		 * @return change direction
		 */
		public int getDirection() {
			return direction;
		}

		/**
		 * @return unix time value when commit occurs
		 */
		public int getCommitTime() {
			return commitTime;
		}

		/**
		 * @return commit id
		 */
		public AbbreviatedObjectId getId() {
			return commitId;
		}

		/**
		 * @return commit author
		 */
		public String getAuthorName() {
			return authorName;
		}

		/**
		 * @return the committer name
		 */
		public String getCommitterName() {
			return committerName;
		}

		/**
		 * @return commit date
		 */
		public Date getCommitDate() {
			return commitDate;
		}

		/**
		 * @return commit short message
		 */
		public String getShortMessage() {
			return shortMessage;
		}

		/**
		 * @return list of changes made by this commit or {@code null} when
		 *         commit doesn't have any changes
		 */
		public Map<String, Change> getChildren() {
			return children;
		}

	}

	/**
	 * Describes single tree or blob change in commit.
	 */
	public static class Change {
		int kind;

		String name;

		AbbreviatedObjectId objectId;

		AbbreviatedObjectId commitId;

		AbbreviatedObjectId remoteCommitId;

		AbbreviatedObjectId remoteObjectId;

		Change() {
			// reduce the visibility of the default constructor
		}

		/**
		 * Describes does this change in incoming/outgoing addition, deletion or
		 * change.
		 *
		 * It uses static values of LEFT, RIGHT, ADDITION, DELETION, CHANGE from
		 * {@link Differencer} class.
		 *
		 * @return kind
		 */
		public int getKind() {
			return kind;
		}

		/**
		 * @return object name
		 */
		public String getName() {
			return name;
		}

		/**
		 * @return id of commit containing this change
		 */
		public AbbreviatedObjectId getCommitId() {
			return commitId;
		}

		/**
		 * @return id of parent commit
		 */
		public AbbreviatedObjectId getRemoteCommitId() {
			return remoteCommitId;
		}

		/**
		 * @return object id
		 */
		public AbbreviatedObjectId getObjectId() {
			return objectId;
		}

		/**
		 * @return remote object id
		 */
		public AbbreviatedObjectId getRemoteObjectId() {
			return remoteObjectId;
		}

	}

	static final AbbreviatedObjectId ZERO_ID = AbbreviatedObjectId
			.fromObjectId(zeroId());

	/**
	 * Scans given {@code repo} and build list of commits between two given
	 * RevCommit objectId's. Each commit contains list of changed resources
	 *
	 * @param repo
	 *            repository that should be scanned
	 * @param srcId
	 *            source RevCommit id
	 * @param dstId
	 *            destination RevCommit id
	 * @return list of {@link Commit} object's between {@code srcId} and
	 *         {@code dstId}
	 * @throws IOException
	 */
	public static List<Commit> build(Repository repo, ObjectId srcId,
			ObjectId dstId) throws IOException {
		if (dstId.equals(srcId))
			return new ArrayList<Commit>(0);

		final RevWalk rw = new RevWalk(repo);

		final RevFlag localFlag = rw.newFlag("local"); //$NON-NLS-1$
		final RevFlag remoteFlag = rw.newFlag("remote"); //$NON-NLS-1$
		final RevFlagSet allFlags = new RevFlagSet();
		allFlags.add(localFlag);
		allFlags.add(remoteFlag);
		rw.carry(allFlags);

		RevCommit srcCommit = rw.parseCommit(srcId);
		srcCommit.add(localFlag);
		rw.markStart(srcCommit);
		srcCommit = null; // free not needed resources

		RevCommit dstCommit = rw.parseCommit(dstId);
		dstCommit.add(remoteFlag);
		rw.markStart(dstCommit);
		dstCommit = null; // free not needed resources

		List<Commit> result = new ArrayList<Commit>();
		for (RevCommit revCommit : rw) {
			if (revCommit.hasAll(allFlags))
				break;

			Commit commit = new Commit();
			commit.shortMessage = revCommit.getShortMessage();
			commit.commitId = AbbreviatedObjectId.fromObjectId(revCommit);
			commit.authorName = revCommit.getAuthorIdent().getName();
			commit.committerName = revCommit.getCommitterIdent().getName();
			commit.commitDate = revCommit.getAuthorIdent().getWhen();
			commit.commitTime = revCommit.getCommitTime();

			if (revCommit.has(localFlag))
				commit.direction = RIGHT;
			else if (revCommit.has(remoteFlag))
				commit.direction = LEFT;
			else
				// should never happen
				continue;

			RevCommit[] parents = revCommit.getParents();
			if (parents.length == 1) // don't show changes in merge commits
				commit.children = getChangedObjects(repo, revCommit,
						parents[0], commit.direction);

			result.add(commit);
		}
		rw.dispose();

		return result;
	}

	private static Map<String, Change> getChangedObjects(Repository repo,
			RevCommit parentCommit, RevCommit remoteCommit, final int direction)
			throws IOException {
		final TreeWalk tw = new TreeWalk(repo);
		tw.addTree(parentCommit.getTree());
		tw.addTree(remoteCommit.getTree());
		tw.setFilter(TreeFilter.ANY_DIFF);
		tw.setRecursive(true);

		final int localTreeId = direction == LEFT ? 0 : 1;
		final int remoteTreeId = direction == LEFT ? 1 : 0;
		final Map<String, Change> result = new HashMap<String, CheckedInCommitsCache.Change>();
		final AbbreviatedObjectId actualCommit = AbbreviatedObjectId
				.fromObjectId(parentCommit);
		final AbbreviatedObjectId remoteComitAbb = AbbreviatedObjectId
				.fromObjectId(remoteCommit);

		MutableObjectId idBuf = new MutableObjectId();
		while (tw.next()) {
			Change change = new Change();
			change.commitId = actualCommit;
			change.remoteCommitId = remoteComitAbb;
			change.name = tw.getNameString();
			tw.getObjectId(idBuf, localTreeId);
			change.objectId = AbbreviatedObjectId.fromObjectId(idBuf);
			tw.getObjectId(idBuf, remoteTreeId);
			change.remoteObjectId = AbbreviatedObjectId.fromObjectId(idBuf);

			calculateAndSetChangeKind(direction, change);

			result.put(tw.getPathString(), change);
		}
		tw.release();

		return result.size() > 0 ? result : null;
	}

	static void calculateAndSetChangeKind(final int direction,
			Change change) {
		if (ZERO_ID.equals(change.objectId)) {
			change.objectId = null; // clear zero id;
			change.kind = direction | Differencer.DELETION;
		} else if (ZERO_ID.equals(change.remoteObjectId)) {
			change.remoteObjectId = null; // clear zero id;
			change.kind = direction | Differencer.ADDITION;
		} else
			change.kind = direction | Differencer.CHANGE;
	}

}
