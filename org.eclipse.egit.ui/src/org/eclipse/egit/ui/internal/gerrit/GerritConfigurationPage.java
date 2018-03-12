/*******************************************************************************
 * Copyright (C) 2011, Stefan Lay <stefan.lay@sap.com>
 * Copyright (C) 2011, Matthias Sohn <matthias.sohn@sap.com>
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.egit.ui.internal.gerrit;

import java.io.IOException;
import java.net.URISyntaxException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.egit.ui.Activator;
import org.eclipse.egit.ui.UIUtils;
import org.eclipse.egit.ui.internal.SWTUtils;
import org.eclipse.egit.ui.internal.UIText;
import org.eclipse.egit.ui.internal.components.RepositorySelectionPage.Protocol;
import org.eclipse.jface.bindings.keys.KeyStroke;
import org.eclipse.jface.bindings.keys.ParseException;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.fieldassist.ContentProposalAdapter;
import org.eclipse.jface.fieldassist.IContentProposal;
import org.eclipse.jface.fieldassist.IContentProposalProvider;
import org.eclipse.jface.fieldassist.TextContentAdapter;
import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.TraverseEvent;
import org.eclipse.swt.events.TraverseListener;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;

/**
 * Wizard page that offers simplified configuration if upstream repository
 * is hosted by Gerrit.
 */
class GerritConfigurationPage extends WizardPage {

	private final static int GERRIT_DEFAULT_SSH_PORT = 29418;

	private static final String GERRIT_HTTP_PATH_PREFIX = "/p"; //$NON-NLS-1$

	private String helpContext = null;

	private Text branch;

	private Group pushConfigurationGroup;

	private Group fetchConfigurationGroup;

	private Group uriGroup;

	private Combo scheme;

	private Text uriText;

	private URIish pushURI;

	private Text user;

	private int eventDepth;

	private final Repository repository;

	private final String remoteName;

	/**
	 * @param repository the respository
	 * @param remoteName the remote name
	 *
	 */
	public GerritConfigurationPage(Repository repository, String remoteName) {
		super(GerritConfigurationPage.class.getName());
		this.repository = repository;
		this.remoteName = remoteName;
		setTitle(UIText.GerritConfigurationPage_title);
		String repositoryName = Activator.getDefault().getRepositoryUtil().getRepositoryName(repository);
		setDescription(MessageFormat.format(
				UIText.GerritConfigurationPage_PageDescription, remoteName,
				repositoryName));

	}

	public void createControl(Composite parent) {
		final Composite panel = new Composite(parent, SWT.NULL);
		final GridLayout layout = new GridLayout();
		layout.numColumns = 1;
		panel.setLayout(layout);

		createURIGroup(panel);
		createPushConfigurationGroup(panel);
		createFetchConfigurationGroup(panel);

		Dialog.applyDialogFont(panel);
		setControl(panel);
		uriText.setFocus();
	}

	private void createURIGroup(Composite panel) {
		uriGroup = SWTUtils.createHFillGroup(panel,
				UIText.GerritConfigurationPage_pushUri,
				SWTUtils.MARGINS_DEFAULT, 2);

		scheme = new Combo(uriGroup, SWT.DROP_DOWN | SWT.READ_ONLY);
		uriText = SWTUtils.createText(uriGroup);

		new Label(uriGroup, SWT.NULL).setText(UIText.GerritConfigurationPage_UserLabel);
		user = SWTUtils.createText(uriGroup);
		user.addModifyListener(new ModifyListener() {
			public void modifyText(final ModifyEvent e) {
				eventDepth++;
				try {
					if (eventDepth == 1) {
						if (pushURI != null) {
							pushURI = pushURI.setUser(user.getText());
							uriText.setText(pushURI.toString());
							checkPage();
						}
					}
				} finally {
					eventDepth--;
				}
			}
		});

		uriText.addModifyListener(new ModifyListener() {
			public void modifyText(final ModifyEvent e) {
				eventDepth++;
				try {
					if (eventDepth == 1) {
						URIish u = new URIish(uriText.getText());
						String newUser = u.getUser();
						user.setText(newUser != null ? newUser : ""); //$NON-NLS-1$
					}
				} catch (URISyntaxException e1) {
					// empty
				} finally {
					eventDepth--;
				}
				checkPage();
			}
		});

		for (Protocol p : Protocol.values()) {
			scheme.add(p.getDefaultScheme());
		}
		scheme.addSelectionListener(new SelectionAdapter() {
			public void widgetSelected(final SelectionEvent e) {
				URIish oldPushURI = pushURI;
				final int idx = scheme.getSelectionIndex();
				pushURI = pushURI.setScheme(scheme.getItem(idx));

				if (Protocol.SSH.handles(pushURI))
					pushURI = pushURI.setPort(GERRIT_DEFAULT_SSH_PORT);
				else
					pushURI = pushURI.setPort(-1);

				if (isHttpProtocol(pushURI))
					pushURI = prependGerritHttpPathPrefix(pushURI);
				else if (isHttpProtocol(oldPushURI))
					pushURI = removeGerritHttpPathPrefix(pushURI);

				uriText.setText(pushURI.toString());
				scheme.setToolTipText(Protocol.values()[idx].getTooltip());
			}
		});
	}

	private void createPushConfigurationGroup(Composite panel) {
		pushConfigurationGroup = SWTUtils.createHFillGroup(panel,
				UIText.GerritConfigurationPage_groupPush,
				SWTUtils.MARGINS_DEFAULT, 3);
		Label branchLabel = new Label(pushConfigurationGroup, SWT.NULL);
		branchLabel
				.setText(UIText.GerritConfigurationPage_labelDestinationBranch);
		// we visualize the prefix here
		Text prefix = new Text(pushConfigurationGroup, SWT.READ_ONLY);
		prefix.setText("refs/for/"); //$NON-NLS-1$
		prefix.setEnabled(false);

		branch = SWTUtils.createText(pushConfigurationGroup);
		branch.addModifyListener(new ModifyListener() {
			public void modifyText(final ModifyEvent e) {
				checkPage();
			}
		});

		// give focus to the branch if label is activated using the mnemonic
		branchLabel.addTraverseListener(new TraverseListener() {
			public void keyTraversed(TraverseEvent e) {
				branch.setFocus();
				branch.selectAll();
			}
		});
		addRefContentProposalToText(branch);
	}

	private void createFetchConfigurationGroup(Composite panel) {
		fetchConfigurationGroup = SWTUtils.createHFillGroup(panel,
				UIText.GerritConfigurationPage_groupFetch,
				SWTUtils.MARGINS_DEFAULT, 2);
		new Label(fetchConfigurationGroup, SWT.NULL)
				.setText(UIText.GerritConfigurationPage_ConfigureFetchReviewNotes);
	}

	/**
	 * @return the push URI for Gerrit code review
	 */
	public URIish getURI() {
		return pushURI;
	}

	/**
	 * @return the branch used in the gerrit push refspec: refs/for/branch
	 */
	public String getBranch() {
		return branch.getText();
	}

	/**
	 * Set the ID for context sensitive help
	 *
	 * @param id
	 *            help context
	 */
	public void setHelpContext(String id) {
		helpContext = id;
	}

	@Override
	public void performHelp() {
		PlatformUI.getWorkbench().getHelpSystem().displayHelp(helpContext);
	}

	/**
	 * @param uri the URI of the source repository
	 */
	public void setSelection(URIish uri) {
		setSelection(uri, null);
	}

	/**
	 * @param uri
	 *            the URI of the source repository
	 * @param targetBranch
	 */
	public void setSelection(URIish uri, String targetBranch) {

		setDefaults(uri, targetBranch);
		checkPage();
	}

	private void setDefaults(URIish uri, String targetBranch) {
		URIish newPushURI = uri;
		if (Protocol.SSH.handles(uri)) {
			newPushURI = newPushURI.setPort(GERRIT_DEFAULT_SSH_PORT);
		} else if (Protocol.GIT.handles(uri)) {
			newPushURI = newPushURI.setScheme(Protocol.SSH.getDefaultScheme());
			newPushURI = newPushURI.setPort(GERRIT_DEFAULT_SSH_PORT);
		} else if (isHttpProtocol(uri)) {
			newPushURI = prependGerritHttpPathPrefix(newPushURI);
		}
		uriText.setText(newPushURI.toString());
		final String uriScheme = newPushURI.getScheme();
		if (uriScheme != null)
			scheme.select(scheme.indexOf(uriScheme));
		branch.setText(targetBranch != null ? targetBranch : Constants.MASTER);
	}

	private boolean isHttpProtocol(URIish uri) {
		return Protocol.HTTP.handles(uri) || Protocol.HTTPS.handles(uri);
	}

	/**
	 * @param u
	 * @return URI with path prefixed for Gerrit smart HTTP support
	 */
	private URIish prependGerritHttpPathPrefix(URIish u) {
		String path = u.getPath();
		if (!path.startsWith(GERRIT_HTTP_PATH_PREFIX))
			return u.setPath(GERRIT_HTTP_PATH_PREFIX + path);
		return u;
	}

	/**
	 * @param u
	 * @return URI without Gerrit smart HTTP path prefix
	 */
	private URIish removeGerritHttpPathPrefix(URIish u) {
		String path = u.getPath();
		if (path.startsWith(GERRIT_HTTP_PATH_PREFIX))
			return u.setPath(path.substring(4));
		return u;
	}

	private void checkPage() {
		try {
			pushURI = new URIish(uriText.getText());
			scheme.select(scheme.indexOf(pushURI.getScheme()));
		} catch (URISyntaxException e) {
			setErrorMessage(e.getLocalizedMessage());
			setPageComplete(false);
			return;
		}
		String branchName = branch.getText();
		if (branchName.length() == 0) {
			setErrorMessage(UIText.GerritConfigurationPage_errorBranchName);
			setPageComplete(false);
			return;
		}

		setErrorMessage(null);
		setPageComplete(true);
	}

	private void addRefContentProposalToText(final Text textField) {
		KeyStroke stroke;
		try {
			stroke = KeyStroke.getInstance("CTRL+SPACE"); //$NON-NLS-1$
			UIUtils.addBulbDecorator(textField, NLS.bind(
					UIText.GerritConfigurationPage_BranchTooltipHover,
					stroke.format()));
		} catch (ParseException e1) {
			Activator.handleError(e1.getMessage(), e1, false);
			stroke = null;
		}

		IContentProposalProvider cp = new IContentProposalProvider() {
			public IContentProposal[] getProposals(String contents, int position) {
				List<IContentProposal> resultList = new ArrayList<IContentProposal>();

				// make the simplest possible pattern check: allow "*"
				// for multiple characters
				String patternString = contents;
				// ignore spaces in the beginning
				while (patternString.length() > 0
						&& patternString.charAt(0) == ' ') {
					patternString = patternString.substring(1);
				}

				// we quote the string as it may contain spaces
				// and other stuff colliding with the Pattern
				patternString = Pattern.quote(patternString);

				patternString = patternString.replaceAll("\\x2A", ".*"); //$NON-NLS-1$ //$NON-NLS-2$

				// make sure we add a (logical) * at the end
				if (!patternString.endsWith(".*")) //$NON-NLS-1$
					patternString = patternString + ".*"; //$NON-NLS-1$

				// let's compile a case-insensitive pattern (assumes ASCII only)
				Pattern pattern;
				try {
					pattern = Pattern.compile(patternString,
							Pattern.CASE_INSENSITIVE);
				} catch (PatternSyntaxException e) {
					pattern = null;
				}

				Set<String> proposals = new TreeSet<String>();

				try {
					// propose the names of the remote tracking
					// branches for the given remote
					Set<String> remotes = repository
							.getRefDatabase()
							.getRefs(Constants.R_REMOTES + remoteName + "/").keySet(); //$NON-NLS-1$
					proposals.addAll(remotes);
				} catch (IOException e) {
					// simply ignore, no proposals then
				}

				for (final String proposal : proposals) {
					if (pattern != null && !pattern.matcher(proposal).matches())
						continue;
					IContentProposal propsal = new BranchContentProposal(
							proposal);
					resultList.add(propsal);
				}

				return resultList.toArray(new IContentProposal[resultList
						.size()]);
			}
		};

		ContentProposalAdapter adapter = new ContentProposalAdapter(textField,
				new TextContentAdapter(), cp, stroke, null);
		// set the acceptance style to always replace the complete content
		adapter.setProposalAcceptanceStyle(ContentProposalAdapter.PROPOSAL_REPLACE);
	}

	private final static class BranchContentProposal implements
			IContentProposal {
		private final String myString;

		BranchContentProposal(String string) {
			myString = string;
		}

		public String getContent() {
			return myString;
		}

		public int getCursorPosition() {
			return 0;
		}

		public String getDescription() {
			return myString;
		}

		public String getLabel() {
			return myString;
		}

		@Override
		public String toString() {
			return getContent();
		}
	}

}
