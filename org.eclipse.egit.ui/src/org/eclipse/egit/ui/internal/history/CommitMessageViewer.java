/*******************************************************************************
 * Copyright (C) 2008, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org>
 * Copyright (C) 2011, Mathias Kinzler <mathias.kinzler@sap.com>
 * Copyright (C) 2011, Jens Baumgart <jens.baumgart@sap.com>
 * Copyright (C) 2011, Stefan Lay <stefan.lay@sap.com>
 * Copyright (C) 2014, Marc-Andre Laperle <marc-andre.laperle@ericsson.com>
 * Copyright (C) 2015, IBM Corporation (Dani Megert <daniel_megert@ch.ibm.com>)
 * Copyright (C) 2015, 2016 Thomas Wolf <thomas.wolf@paranor.ch>
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.egit.ui.internal.history;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.ListenerList;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.egit.core.AdapterUtils;
import org.eclipse.egit.ui.Activator;
import org.eclipse.egit.ui.UIPreferences;
import org.eclipse.egit.ui.UIUtils;
import org.eclipse.egit.ui.internal.ActionUtils;
import org.eclipse.egit.ui.internal.UIText;
import org.eclipse.egit.ui.internal.actions.BooleanPrefAction;
import org.eclipse.egit.ui.internal.dialogs.HyperlinkSourceViewer;
import org.eclipse.egit.ui.internal.handler.IGlobalActionProvider;
import org.eclipse.egit.ui.internal.history.FormatJob.FormatResult;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.preference.IPersistentPreferenceStore;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.JFacePreferences;
import org.eclipse.jface.text.DefaultTextDoubleClickStrategy;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentPartitioner;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.TextUtilities;
import org.eclipse.jface.text.hyperlink.IHyperlink;
import org.eclipse.jface.text.hyperlink.IHyperlinkDetector;
import org.eclipse.jface.text.hyperlink.IHyperlinkDetectorExtension2;
import org.eclipse.jface.text.rules.FastPartitioner;
import org.eclipse.jface.text.rules.IPartitionTokenScanner;
import org.eclipse.jface.text.rules.IToken;
import org.eclipse.jface.text.rules.Token;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jgit.events.ListenerHandle;
import org.eclipse.jgit.events.RefsChangedEvent;
import org.eclipse.jgit.events.RefsChangedListener;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revplot.PlotCommit;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.IWorkbenchPartSite;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.part.IPageSite;
import org.eclipse.ui.progress.IWorkbenchSiteProgressService;

class CommitMessageViewer extends HyperlinkSourceViewer
		implements IGlobalActionProvider {

	static final String HEADER_CONTENT_TYPE = "__egit_commit_msg_header"; //$NON-NLS-1$

	static final String FOOTER_CONTENT_TYPE = "__egit_commit_msg_footer"; //$NON-NLS-1$

	// notified when clicking on a link in the message (branch, commit...)
	private final ListenerList navListeners = new ListenerList();

	// listener to detect changes in the wrap and fill preferences
	private final IPropertyChangeListener listener;

	// Listener to react on syntax coloring preferences changes
	private final IPropertyChangeListener syntaxColoringListener;

	// the current repository
	private Repository db;

	// the "input" (set by setInput())
	private PlotCommit<?> commit;

	// formatting option to fill the lines
	private boolean fill;

	private FormatJob formatJob;

	private final IWorkbenchPartSite partSite;

	private List<Ref> allRefs;

	private ListenerHandle refsChangedListener;

	private BooleanPrefAction showTagSequencePrefAction;

	private BooleanPrefAction showBranchSequencePrefAction;

	private BooleanPrefAction wrapCommentsPrefAction;

	private BooleanPrefAction fillParagraphsPrefAction;

	private final Set<IAction> globalActions = new HashSet<>();

	CommitMessageViewer(final Composite parent, final IPageSite site, IWorkbenchPartSite partSite) {
		super(parent, null, SWT.READ_ONLY);
		this.partSite = partSite;

		final StyledText t = getTextWidget();
		t.setFont(UIUtils.getFont(UIPreferences.THEME_CommitMessageFont));

		setTextDoubleClickStrategy(new DefaultTextDoubleClickStrategy(),
				IDocument.DEFAULT_CONTENT_TYPE);
		activatePlugins();

		// react on changes in the fill and wrap preferences
		listener = new IPropertyChangeListener() {
			@Override
			public void propertyChange(PropertyChangeEvent event) {
				String property = event.getProperty();
				if (UIPreferences.RESOURCEHISTORY_SHOW_COMMENT_FILL
						.equals(property)) {
					setFill(((Boolean) event.getNewValue()).booleanValue());
				} else
					if (UIPreferences.HISTORY_SHOW_TAG_SEQUENCE.equals(property)
							|| UIPreferences.HISTORY_SHOW_BRANCH_SEQUENCE
									.equals(property)
							|| UIPreferences.DATE_FORMAT.equals(property)
							|| UIPreferences.DATE_FORMAT_CHOICE
									.equals(property)) {
					format();
				}
			}
		};
		IPreferenceStore store = Activator.getDefault().getPreferenceStore();
		store.addPropertyChangeListener(listener);
		fill = store
				.getBoolean(UIPreferences.RESOURCEHISTORY_SHOW_COMMENT_FILL);

		// React on changes in the JFace color preferences by updating the view
		syntaxColoringListener = new IPropertyChangeListener() {
			@Override
			public void propertyChange(PropertyChangeEvent event) {
				if (JFacePreferences.HYPERLINK_COLOR
						.equals(event.getProperty())) {
					if (!t.isDisposed()) {
						t.getDisplay().asyncExec(new Runnable() {
							@Override
							public void run() {
								if (!t.isDisposed()) {
									refresh();
								}
							}
						});
					}
				}
			}
		};
		JFacePreferences.getPreferenceStore()
				.addPropertyChangeListener(syntaxColoringListener);

		// global action handlers for select all and copy
		final IAction selectAll = ActionUtils.createGlobalAction(
				ActionFactory.SELECT_ALL,
				() -> doOperation(ITextOperationTarget.SELECT_ALL),
				() -> canDoOperation(ITextOperationTarget.SELECT_ALL));
		final IAction copy = ActionUtils.createGlobalAction(ActionFactory.COPY,
				() -> doOperation(ITextOperationTarget.COPY),
				() -> canDoOperation(ITextOperationTarget.COPY));
		globalActions.add(selectAll);
		globalActions.add(copy);

		final MenuManager mgr = new MenuManager();
		Control c = getControl();
		c.setMenu(mgr.createContextMenu(c));

		IPersistentPreferenceStore pstore = (IPersistentPreferenceStore) store;

		showBranchSequencePrefAction = new BooleanPrefAction(pstore,
				UIPreferences.HISTORY_SHOW_BRANCH_SEQUENCE,
				UIText.ResourceHistory_ShowBranchSequence) {
			@Override
			protected void apply(boolean value) {
				// nothing, just toggle
			}
		};
		mgr.add(showBranchSequencePrefAction);

		showTagSequencePrefAction = new BooleanPrefAction(pstore,
				UIPreferences.HISTORY_SHOW_TAG_SEQUENCE,
				UIText.ResourceHistory_ShowTagSequence) {
			@Override
			protected void apply(boolean value) {
				// nothing, just toggle
			}
		};
		mgr.add(showTagSequencePrefAction);

		wrapCommentsPrefAction = new BooleanPrefAction(pstore,
				UIPreferences.RESOURCEHISTORY_SHOW_COMMENT_WRAP,
				UIText.ResourceHistory_toggleCommentWrap) {
			@Override
			protected void apply(boolean value) {
				// nothing, just toggle
			}
		};
		mgr.add(wrapCommentsPrefAction);

		fillParagraphsPrefAction = new BooleanPrefAction(pstore,
				UIPreferences.RESOURCEHISTORY_SHOW_COMMENT_FILL,
				UIText.ResourceHistory_toggleCommentFill) {
			@Override
			protected void apply(boolean value) {
				// nothing, just toggle
			}
		};
		mgr.add(fillParagraphsPrefAction);
	}

	void addDoneListenerToFormatJob() {
		formatJob.addJobChangeListener(new JobChangeAdapter() {
			@Override
			public void done(IJobChangeEvent event) {
				if (!event.getResult().isOK())
					return;
				final StyledText text = getTextWidget();
				if (text == null || text.isDisposed())
					return;
				final FormatResult result = ((FormatJob) event.getJob())
						.getFormatResult();
				text.getDisplay().asyncExec(new Runnable() {
					@Override
					public void run() {
						applyFormatJobResultInUI(result);
					}
				});
			}
		});
	}

	@Override
	protected void handleDispose() {
		if (formatJob != null) {
			formatJob.cancel();
			formatJob = null;
		}
		Activator.getDefault().getPreferenceStore()
				.removePropertyChangeListener(listener);
		JFacePreferences.getPreferenceStore()
				.removePropertyChangeListener(syntaxColoringListener);
		if (refsChangedListener != null)
			refsChangedListener.remove();
		refsChangedListener = null;
		showBranchSequencePrefAction.dispose();
		showTagSequencePrefAction.dispose();
		wrapCommentsPrefAction.dispose();
		fillParagraphsPrefAction.dispose();

		super.handleDispose();
	}

	void addCommitNavigationListener(final CommitNavigationListener l) {
		navListeners.add(l);
	}

	void removeCommitNavigationListener(final CommitNavigationListener l) {
		navListeners.remove(l);
	}

	@Override
	public void setInput(final Object input) {
		// right-clicking on a commit will fire selection change events,
		// so we only rebuild this when the commit did in fact change
		if (input == commit)
			return;
		commit = (PlotCommit<?>) input;
		if (refsChangedListener != null) {
			refsChangedListener.remove();
			refsChangedListener = null;
		}

		if (db != null) {
			allRefs = getBranches(db);
			refsChangedListener = db.getListenerList().addRefsChangedListener(
					new RefsChangedListener() {

						@Override
						public void onRefsChanged(RefsChangedEvent event) {
							allRefs = getBranches(db);
						}
					});
		}
		format();
	}

	@Override
	public Object getInput() {
		return commit;
	}

	void setRepository(final Repository repository) {
		this.db = repository;
	}

	private static List<Ref> getBranches(Repository repo)  {
		List<Ref> ref = new ArrayList<>();
		try {
			RefDatabase refDb = repo.getRefDatabase();
			ref.addAll(refDb.getRefs(Constants.R_HEADS).values());
			ref.addAll(refDb.getRefs(Constants.R_REMOTES).values());
		} catch (IOException e) {
			Activator.logError(e.getMessage(), e);
		}
		return ref;
	}

	private Repository getRepository() {
		if (db == null)
			throw new IllegalStateException("Repository has not been set"); //$NON-NLS-1$
		return db;
	}

	private void format() {
		if (db == null || commit == null) {
			setDocument(new Document("")); //$NON-NLS-1$
			return;
		}
		if (formatJob != null && formatJob.getState() != Job.NONE)
			formatJob.cancel();
		scheduleFormatJob();
	}

	private void scheduleFormatJob() {
		IWorkbenchSiteProgressService siteService = AdapterUtils.adapt(partSite, IWorkbenchSiteProgressService.class);
		if (siteService == null)
			return;
		FormatJob.FormatRequest formatRequest = new FormatJob.FormatRequest(
				getRepository(), commit, fill, allRefs);
		formatJob = new FormatJob(formatRequest);
		addDoneListenerToFormatJob();
		siteService.schedule(formatJob, 0 /* now */, true /*
														 * use the half-busy
														 * cursor in the part
														 */);
	}

	private void applyFormatJobResultInUI(FormatResult formatResult) {
		StyledText text = getTextWidget();
		if (!UIUtils.isUsable(text))
			return;

		setDocument(new CommitDocument(formatResult));
	}

	private class ObjectHyperlink implements IHyperlink {

		private final GitCommitReference link;

		public ObjectHyperlink(GitCommitReference link) {
			this.link = link;
		}

		@Override
		public IRegion getHyperlinkRegion() {
			return link.getRegion();
		}

		@Override
		public String getTypeLabel() {
			return null;
		}

		@Override
		public String getHyperlinkText() {
			return link.getTarget().name();
		}

		@Override
		public void open() {
			for (final Object l : navListeners.getListeners()) {
				((CommitNavigationListener) l).showCommit(link.getTarget());
			}
		}

	}

	private class CommitDocument extends Document {

		private final List<IHyperlink> hyperlinks;

		private final int headerEnd;

		private final int footerStart;

		public CommitDocument(FormatResult format) {
			super(format.getCommitInfo());
			headerEnd = format.getHeaderEnd();
			footerStart = format.getFooterStart();
			List<GitCommitReference> knownLinks = format.getKnownLinks();
			hyperlinks = new ArrayList<>(knownLinks.size());
			for (GitCommitReference o : knownLinks) {
				hyperlinks.add(new ObjectHyperlink(o));
			}
			IDocumentPartitioner partitioner = new FastPartitioner(
					new CommitPartitionTokenScanner(),
					new String[] { IDocument.DEFAULT_CONTENT_TYPE,
							HEADER_CONTENT_TYPE, FOOTER_CONTENT_TYPE });
			partitioner.connect(this);
			this.setDocumentPartitioner(partitioner);
		}

		public List<IHyperlink> getKnownHyperlinks() {
			return hyperlinks;
		}

		public int getHeaderEnd() {
			return headerEnd;
		}

		public int getFooterStart() {
			return footerStart;
		}
	}

	private static class CommitPartitionTokenScanner
			implements IPartitionTokenScanner {

		private static final IToken HEADER = new Token(HEADER_CONTENT_TYPE);

		private static final IToken BODY = new Token(
				IDocument.DEFAULT_CONTENT_TYPE);

		private static final IToken FOOTER = new Token(FOOTER_CONTENT_TYPE);

		private int headerEnd;

		private int footerStart;

		private int currentOffset;

		private int end;

		private int tokenStart;

		@Override
		public void setRange(IDocument document, int offset, int length) {
			if (document instanceof CommitDocument) {
				CommitDocument d = (CommitDocument) document;
				headerEnd = d.getHeaderEnd();
				footerStart = d.getFooterStart();
			} else {
				headerEnd = 0;
				footerStart = document.getLength();
			}
			currentOffset = offset;
			end = offset + length;
			tokenStart = -1;
		}

		@Override
		public IToken nextToken() {
			tokenStart = currentOffset;
			if (currentOffset < end) {
				if (currentOffset < headerEnd) {
					currentOffset = Math.min(headerEnd, end);
					return HEADER;
				} else if (currentOffset < footerStart) {
					currentOffset = Math.min(footerStart, end);
					return BODY;
				} else {
					currentOffset = end;
					return FOOTER;
				}
			}
			return Token.EOF;
		}

		@Override
		public int getTokenOffset() {
			return tokenStart;
		}

		@Override
		public int getTokenLength() {
			return currentOffset - tokenStart;
		}

		@Override
		public void setPartialRange(IDocument document, int offset, int length,
				String contentType, int partitionOffset) {
			setRange(document, offset, length);
		}

	}

	static class KnownHyperlinksDetector
			implements IHyperlinkDetector, IHyperlinkDetectorExtension2 {

		@Override
		public IHyperlink[] detectHyperlinks(ITextViewer textViewer,
				IRegion region, boolean canShowMultipleHyperlinks) {
			IDocument document = textViewer.getDocument();
			if (document instanceof CommitDocument) {
				List<IHyperlink> knownLinks = ((CommitDocument) document)
						.getKnownHyperlinks();
				List<IHyperlink> result = new ArrayList<>();
				for (IHyperlink link : knownLinks) {
					IRegion linkRegion = link.getHyperlinkRegion();
					if (TextUtilities.overlaps(linkRegion, region)) {
						result.add(link);
					}
				}
				if (!result.isEmpty()) {
					return result.toArray(new IHyperlink[result.size()]);
				}
			}
			return null;
		}

		@Override
		public int getStateMask() {
			return -1;
		}

	}

	private void setFill(boolean fill) {
		this.fill = fill;
		format();
	}

	@Override
	public Viewer getViewer() {
		return this;
	}

	@Override
	public Collection<IAction> getActions() {
		return globalActions;
	}

}
