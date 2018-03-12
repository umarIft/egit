/*******************************************************************************
 * Copyright (c) 2012, 2016 Matthias Sohn <matthias.sohn@sap.com> and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Thomas Wolf <thomas.wolf@paranor.ch> - Bugs 479964, 483664
 *******************************************************************************/
package org.eclipse.egit.ui.view.repositories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.Arrays;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.egit.core.internal.indexdiff.IndexDiffCache;
import org.eclipse.egit.ui.Activator;
import org.eclipse.egit.ui.JobFamilies;
import org.eclipse.egit.ui.internal.UIText;
import org.eclipse.egit.ui.test.ContextMenuHelper;
import org.eclipse.egit.ui.test.TestUtil;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jgit.api.SubmoduleAddCommand;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.swtbot.swt.finder.junit.SWTBotJunit4ClassRunner;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotCheckBox;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotShell;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotTree;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotTreeItem;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * SWTBot Tests for the Git Repositories View (repository deletion)
 */
@RunWith(SWTBotJunit4ClassRunner.class)
public class GitRepositoriesViewRepoDeletionTest extends
		GitRepositoriesViewTestBase {

	private static final String DELETE_REPOSITORY_CONTEXT_MENU_LABEL = "RepoViewDeleteRepository.label";

	private static final String REMOVE_REPOSITORY_FROM_VIEW_CONTEXT_MENU_LABEL = "RepoViewRemove.label";

	private File repositoryFile;

	@Before
	public void before() throws Exception {
		repositoryFile = createProjectAndCommitToRepository();
	}

	@Test
	public void testDeleteRepositoryWithContentOk() throws Exception {
		deleteAllProjects();
		assertProjectExistence(PROJ1, false);
		clearView();
		Activator.getDefault().getRepositoryUtil().addConfiguredRepository(
				repositoryFile);
		shareProjects(repositoryFile);
		assertProjectExistence(PROJ1, true);
		refreshAndWait();
		assertHasRepo(repositoryFile);
		SWTBotTree tree = getOrOpenView().bot().tree();
		tree.getAllItems()[0].select();
		ContextMenuHelper.clickContextMenu(tree, myUtil
				.getPluginLocalizedValue(DELETE_REPOSITORY_CONTEXT_MENU_LABEL));
		SWTBotShell shell = bot.shell(UIText.DeleteRepositoryConfirmDialog_DeleteRepositoryWindowTitle);
		shell.activate();
		shell.bot()
				.checkBox(
						UIText.DeleteRepositoryConfirmDialog_DeleteGitDirCheckbox)
				.select();
		shell.bot()
				.checkBox(
						UIText.DeleteRepositoryConfirmDialog_DeleteWorkingDirectoryCheckbox)
				.select();
		shell.bot().button(IDialogConstants.OK_LABEL).click();
		TestUtil.joinJobs(JobFamilies.REPOSITORY_DELETE);

		refreshAndWait();
		assertEmpty();
		assertProjectExistence(PROJ1, false);
		assertFalse(repositoryFile.exists());
		assertFalse(new File(repositoryFile.getParentFile(), PROJ1).exists());
		assertFalse(repositoryFile.getParentFile().exists());
	}

	@Test
	public void testDeleteRepositoryKeepProjectsBug479964() throws Exception {
		deleteAllProjects();
		assertProjectExistence(PROJ1, false);
		clearView();
		Activator.getDefault().getRepositoryUtil()
				.addConfiguredRepository(repositoryFile);
		shareProjects(repositoryFile);
		assertProjectExistence(PROJ1, true);
		refreshAndWait();
		assertHasRepo(repositoryFile);
		SWTBotTree tree = getOrOpenView().bot().tree();
		tree.getAllItems()[0].select();
		ContextMenuHelper.clickContextMenu(tree, myUtil
				.getPluginLocalizedValue(DELETE_REPOSITORY_CONTEXT_MENU_LABEL));
		SWTBotShell shell = bot.shell(
				UIText.DeleteRepositoryConfirmDialog_DeleteRepositoryWindowTitle);
		shell.activate();
		shell.bot()
				.checkBox(
						UIText.DeleteRepositoryConfirmDialog_DeleteGitDirCheckbox)
				.select();
		SWTBotCheckBox checkbox = shell.bot().checkBox(
				UIText.DeleteRepositoryConfirmDialog_DeleteWorkingDirectoryCheckbox);
		checkbox.select();
		checkbox.deselect();
		// Now "Remove project from workspace" is selected, but "Delete working
		// tree" is not.
		shell.bot().button(IDialogConstants.OK_LABEL).click();
		TestUtil.joinJobs(JobFamilies.REPOSITORY_DELETE);

		refreshAndWait();
		assertEmpty();
		assertProjectExistence(PROJ1, false);
		assertFalse(repositoryFile.exists());
		assertTrue(
				new File(repositoryFile.getParentFile(), PROJ1).isDirectory());
	}

	@Test
	public void testRemoveRepositoryRemoveFromCachesBug483664()
			throws Exception {
		deleteAllProjects();
		assertProjectExistence(PROJ1, false);
		clearView();
		refreshAndWait();

		Activator.getDefault().getRepositoryUtil()
				.addConfiguredRepository(repositoryFile);
		refreshAndWait();
		assertHasRepo(repositoryFile);
		SWTBotTree tree = getOrOpenView().bot().tree();
		tree.getAllItems()[0].select();
		ContextMenuHelper.clickContextMenuSync(tree,
				myUtil.getPluginLocalizedValue(
				REMOVE_REPOSITORY_FROM_VIEW_CONTEXT_MENU_LABEL));
		TestUtil.joinJobs(JobFamilies.REPOSITORY_DELETE);
		refreshAndWait();
		assertEmpty();

		assertTrue(repositoryFile.exists());
		assertTrue(
				new File(repositoryFile.getParentFile(), PROJ1).isDirectory());
		waitForDecorations();
		closeGitViews();
		TestUtil.waitForJobs(500, 5000);
		// Session properties are stored in the Eclipse resource tree as part of
		// the resource info. org.eclipse.core.internal.dtree.DataTreeLookup has
		// a static LRU cache of lookup instances to avoid excessive strain on
		// the garbage collector due to constantly allocating and then
		// forgetting instances. These lookup objects may contain things
		// recently queried or modified in the resource tree, such as session
		// properties. As a result, the session properties of a deleted resource
		// remain around a little longer than expected: to be precise, exactly
		// 100 more queries on the Eclipse resource tree until the entry
		// containing the session property is recycled. We use session
		// properties to store the RepositoryMappings, which reference the
		// repository.
		//
		// Make sure we clear that cache:
		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
		IProject project = root.getProject(PROJ1);
		for (int i = 0; i < 101; i++) {
			// Number of iterations at least DataTreeLookup.POOL_SIZE!
			// Use up one DataTreeLookup instance:
			project.create(null);
			if (i == 0) {
				// Furthermore, the WorkbenchSourceProvider has still a
				// reference to the last selection, which is our now long
				// removed repository node! Arguably that's a strange thing, but
				// strictly speaking, since there is no guarantee _when_ a
				// weakly referenced object is removed, not even making
				// WorkbenchSourceProvider.lastShowInSelection a WeakReference
				// might help. Therefore, let's make sure that the last "show
				// in" selection is no longer the RepositoryNode, which also
				// still has a reference to the repository. That last "show in"
				// selection is set when the "Shown in..." context menu is
				// filled, which happens when the project explorer's context
				// menu is activated. So we have to open that menu at least once
				// with a different selection.
				SWTBotTree explorerTree = TestUtil.getExplorerTree();
				SWTBotTreeItem projectNode = TestUtil.navigateTo(explorerTree,
						PROJ1);
				projectNode.select();
				ContextMenuHelper.isContextMenuItemEnabled(explorerTree, "New");
			}
			project.delete(true, true, null);
		}
		// All this project creation and deletion does trigger again index diff
		// updates.
		TestUtil.joinJobs(
				org.eclipse.egit.core.JobFamilies.INDEX_DIFF_CACHE_UPDATE);
		waitForDecorations();
		// And we may have the RepositoryChangeScanner running:
		TestUtil.waitForJobs(500, 5000);
		// Finally... Java does not give any guarantees about when exactly a
		// only weakly reachable object is finalized and garbage collected.
		waitForFinalization(5000);
		// Experience shows that an explicit garbage collection run very
		// often does reclaim only weakly reachable objects an set the weak
		// references referents to null, but not even that can be guaranteed!
		// Whether it does or not may also depend on the configuration of
		// the JVM (such as through command-line arguments).
		List<String> configuredRepos = org.eclipse.egit.core.Activator
				.getDefault().getRepositoryUtil().getConfiguredRepositories();
		assertTrue("Expected no configured repositories",
				configuredRepos.isEmpty());
		Repository[] repositories = org.eclipse.egit.core.Activator.getDefault()
				.getRepositoryCache().getAllRepositories();
		// if (repositories.length > 0) {
		// System.out.println(
		// "**** FOUND " + Arrays.asList(repositories).toString());
		// bot.getDisplay().syncExec(new Runnable() {
		//
		// @Override
		// public void run() {
		// MessageDialog.openInformation(bot.activeShell().widget,
		// "Stop", "Waiting...");
		// }
		// });
		// bot.waitUntilWidgetAppears(new ICondition() {
		//
		// @Override
		// public boolean test() throws Exception {
		// Shell shell = bot.getFinder().activeShell();
		// return !shell.isDisposed()
		// && "Stop".equals(shell.getText());
		// }
		//
		// @Override
		// public void init(SWTBot bot) {
		// // TODO Auto-generated method stub
		//
		// }
		//
		// @Override
		// public String getFailureMessage() {
		// // TODO Auto-generated method stub
		// return null;
		// }
		// });
		// bot.waitWhile(new ICondition() {
		//
		// @Override
		// public boolean test() throws Exception {
		// Shell shell = bot.getFinder().activeShell();
		// return !shell.isDisposed()
		// && "Stop".equals(shell.getText());
		// }
		//
		// @Override
		// public void init(SWTBot bot) {
		// // TODO Auto-generated method stub
		//
		// }
		//
		// @Override
		// public String getFailureMessage() {
		// // TODO Auto-generated method stub
		// return null;
		// }
		// });
		// }
		assertEquals("Expected no cached repositories", "[]",
				Arrays.asList(repositories).toString());
		// A pity we can't mock the cache.
		IndexDiffCache indexDiffCache = org.eclipse.egit.core.Activator
				.getDefault().getIndexDiffCache();
		assertEquals("Expected no IndexDiffCache entries", "[]",
				indexDiffCache.currentCacheEntries().toString());
	}

	@Test
	public void testDeleteSubmoduleRepository() throws Exception {
		deleteAllProjects();
		assertProjectExistence(PROJ1, false);
		clearView();
		Activator.getDefault().getRepositoryUtil()
				.addConfiguredRepository(repositoryFile);
		shareProjects(repositoryFile);
		assertProjectExistence(PROJ1, true);
		refreshAndWait();
		assertHasRepo(repositoryFile);

		Repository db = lookupRepository(repositoryFile);
		SubmoduleAddCommand command = new SubmoduleAddCommand(db);
		String path = "sub";
		command.setPath(path);
		String uri = db.getDirectory().toURI().toString();
		command.setURI(uri);
		Repository subRepo = command.call();
		assertNotNull(subRepo);
		subRepo.close();

		refreshAndWait();

		SWTBotTree tree = getOrOpenView().bot().tree();
		tree.getAllItems()[0]
				.expand()
				.expandNode(
						UIText.RepositoriesViewLabelProvider_SubmodulesNodeText)
				.getItems()[0].select();
		ContextMenuHelper.clickContextMenu(tree, myUtil
				.getPluginLocalizedValue(DELETE_REPOSITORY_CONTEXT_MENU_LABEL));
		SWTBotShell shell = bot
				.shell(UIText.DeleteRepositoryConfirmDialog_DeleteRepositoryWindowTitle);
		shell.activate();
		shell.bot()
				.checkBox(
						UIText.DeleteRepositoryConfirmDialog_DeleteGitDirCheckbox)
				.select();
		shell.bot()
				.checkBox(
						UIText.DeleteRepositoryConfirmDialog_DeleteWorkingDirectoryCheckbox)
				.select();
		shell.bot().button(IDialogConstants.OK_LABEL).click();
		TestUtil.joinJobs(JobFamilies.REPOSITORY_DELETE);

		refreshAndWait();
		assertFalse(subRepo.getDirectory().exists());
		assertFalse(subRepo.getWorkTree().exists());
	}

	@SuppressWarnings("restriction")
	private void waitForDecorations() throws Exception {
		TestUtil.joinJobs(
				org.eclipse.ui.internal.decorators.DecoratorManager.FAMILY_DECORATE);
	}

	/**
	 * Best-effort attempt to get finalization to occur.
	 *
	 * @param maxMillis
	 *            maximum amount of time in milliseconds to try getting the
	 *            garbage collector to finalize objects
	 */
	private void waitForFinalization(int maxMillis) {
		long stop = System.currentTimeMillis() + maxMillis;
		MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
		do {
			System.gc();
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		} while (System.currentTimeMillis() < stop
				&& memoryBean.getObjectPendingFinalizationCount() > 0);
	}
}
