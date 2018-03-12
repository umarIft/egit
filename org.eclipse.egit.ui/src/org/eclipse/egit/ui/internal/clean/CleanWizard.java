/*******************************************************************************
 * Copyright (C) 2012, Markus Duft <markus.duft@salomon.at>
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package org.eclipse.egit.ui.internal.clean;

import org.eclipse.egit.ui.Activator;
import org.eclipse.egit.ui.internal.UIText;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.osgi.util.NLS;

/**
 * Represents the contents of the Clean Wizard
 */
public class CleanWizard extends Wizard {

	private CleanRepositoryPage cleanPage;

	/**
	 * Repository to be cleaned
	 */
	private final Repository repository;

	/**
	 * Creates a new Wizard and adds all required pages.
	 * @param repository the repository to clean
	 */
	public CleanWizard(Repository repository) {
		this.repository = repository;
		setNeedsProgressMonitor(true);
		final String repoName = Activator.getDefault().getRepositoryUtil()
				.getRepositoryName(repository);
		setWindowTitle(NLS.bind(UIText.CleanWizard_title, repoName));
	}

	@Override
	public void addPages() {
		cleanPage = new CleanRepositoryPage(repository);
		addPage(cleanPage);
	}

	@Override
	public boolean performFinish() {
		cleanPage.finish();
		return true;
	}

}
