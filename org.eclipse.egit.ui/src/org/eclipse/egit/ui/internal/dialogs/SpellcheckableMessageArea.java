/*******************************************************************************
 * Copyright (C) 2010, Benjamin Muskalla <bmuskalla@eclipsesource.com>
 * Copyright (C) 2011, Matthias Sohn <matthias.sohn@sap.com>
 * Copyright (C) 2011-2012, IBM Corporation
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Benjamin Muskalla (EclipseSource) - initial implementation
 *    Tomasz Zarna (IBM) - show whitespace action, bug 371353
 *******************************************************************************/
package org.eclipse.egit.ui.internal.dialogs;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.egit.core.internal.Utils;
import org.eclipse.egit.ui.Activator;
import org.eclipse.egit.ui.UIPreferences;
import org.eclipse.egit.ui.UIText;
import org.eclipse.egit.ui.UIUtils;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.action.SubMenuManager;
import org.eclipse.jface.commands.ActionHandler;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IPainter;
import org.eclipse.jface.text.ITextListener;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerExtension2;
import org.eclipse.jface.text.MarginPainter;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.TextEvent;
import org.eclipse.jface.text.WhitespaceCharacterPainter;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.IContentAssistant;
import org.eclipse.jface.text.quickassist.IQuickAssistInvocationContext;
import org.eclipse.jface.text.quickassist.IQuickAssistProcessor;
import org.eclipse.jface.text.reconciler.IReconciler;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.AnnotationModel;
import org.eclipse.jface.text.source.IAnnotationAccess;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.ISharedTextColors;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.FocusEvent;
import org.eclipse.swt.events.FocusListener;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.ActiveShellExpression;
import org.eclipse.ui.IWorkbenchCommandConstants;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.editors.text.TextSourceViewerConfiguration;
import org.eclipse.ui.handlers.IHandlerActivation;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.texteditor.AbstractTextEditor;
import org.eclipse.ui.texteditor.AnnotationPreference;
import org.eclipse.ui.texteditor.DefaultMarkerAnnotationAccess;
import org.eclipse.ui.texteditor.ITextEditorActionDefinitionIds;
import org.eclipse.ui.texteditor.IUpdate;
import org.eclipse.ui.texteditor.MarkerAnnotationPreferences;
import org.eclipse.ui.texteditor.SourceViewerDecorationSupport;

/**
 * Text field with support for spellchecking.
 */
public class SpellcheckableMessageArea extends Composite {

	static final int MAX_LINE_WIDTH = 72;

	// Letter or number
	private static final Pattern STARTS_WITH_WORD = Pattern.compile("[\\p{L}\\p{N}].*"); //$NON-NLS-1$

	private static class TextViewerAction extends Action implements IUpdate {

		private int fOperationCode= -1;
		private ITextOperationTarget fOperationTarget;

		/**
		 * Creates a new action.
		 *
		 * @param viewer the viewer
		 * @param operationCode the opcode
		 */
		public TextViewerAction(ITextViewer viewer, int operationCode) {
			fOperationCode= operationCode;
			fOperationTarget= viewer.getTextOperationTarget();
			update();
		}

		/**
		 * Updates the enabled state of the action.
		 * Fires a property change if the enabled state changes.
		 *
		 * @see Action#firePropertyChange(String, Object, Object)
		 */
		public void update() {
			// XXX: workaround for https://bugs.eclipse.org/bugs/show_bug.cgi?id=206111
			if (fOperationCode == ITextOperationTarget.REDO)
				return;

			boolean wasEnabled= isEnabled();
			boolean isEnabled= (fOperationTarget != null && fOperationTarget.canDoOperation(fOperationCode));
			setEnabled(isEnabled);

			if (wasEnabled != isEnabled)
				firePropertyChange(ENABLED, wasEnabled ? Boolean.TRUE : Boolean.FALSE, isEnabled ? Boolean.TRUE : Boolean.FALSE);
		}

		/**
		 * @see Action#run()
		 */
		public void run() {
			if (fOperationCode != -1 && fOperationTarget != null)
				fOperationTarget.doOperation(fOperationCode);
		}
	}

	private static abstract class TextEditorPropertyAction extends Action implements IPropertyChangeListener {

		private SourceViewer viewer;
		private String preferenceKey;
		private IPreferenceStore store;

		public TextEditorPropertyAction(String label, SourceViewer viewer, String preferenceKey) {
			super(label, IAction.AS_CHECK_BOX);
			this.viewer = viewer;
			this.preferenceKey = preferenceKey;
			this.store = EditorsUI.getPreferenceStore();
			if (store != null)
				store.addPropertyChangeListener(this);
			synchronizeWithPreference();
		}

		public void propertyChange(PropertyChangeEvent event) {
			if (event.getProperty().equals(getPreferenceKey()))
				synchronizeWithPreference();
		}

		protected void synchronizeWithPreference() {
			boolean checked = false;
			if (store != null)
				checked = store.getBoolean(getPreferenceKey());
			if (checked != isChecked()) {
				setChecked(checked);
				toggleState(checked);
			} else if (checked) {
				toggleState(false);
				toggleState(true);
			}
		}

		private String getPreferenceKey() {
			return preferenceKey;
		}

		@Override
		public void run() {
			toggleState(isChecked());
			if (store != null)
				store.setValue(getPreferenceKey(), isChecked());
		}

		public void dispose() {
			if (store != null)
				store.removePropertyChangeListener(this);
		}

		/**
		 * @param checked
		 *            new state
		 */
		abstract protected void toggleState(boolean checked);

		protected ITextViewer getTextViewer() {
			return viewer;
		}
	}

	private final SourceViewer sourceViewer;

	private ModifyListener hardWrapModifyListener;

	/**
	 * @param parent
	 * @param initialText
	 */
	public SpellcheckableMessageArea(Composite parent, String initialText) {
		this(parent, initialText, SWT.BORDER);
	}

	/**
	 * @param parent
	 * @param initialText
	 * @param styles
	 */
	public SpellcheckableMessageArea(Composite parent, String initialText,
			int styles) {
		this(parent, initialText, false, styles);
	}

	/**
	 * @param parent
	 * @param initialText
	 * @param readOnly
	 * @param styles
	 */
	public SpellcheckableMessageArea(Composite parent, String initialText,
			boolean readOnly,
			int styles) {
		super(parent, styles);
		setLayout(new FillLayout());

		AnnotationModel annotationModel = new AnnotationModel();
		sourceViewer = new SourceViewer(this, null, null, true, SWT.MULTI
				| SWT.V_SCROLL | SWT.WRAP);
		getTextWidget().setFont(UIUtils
				.getFont(UIPreferences.THEME_CommitMessageEditorFont));

		int endSpacing = 2;
		int textWidth = getCharWidth() * MAX_LINE_WIDTH + endSpacing;
		int textHeight = getLineHeight() * 7;
		Point size = getTextWidget().computeSize(textWidth, textHeight);
		getTextWidget().setSize(size);

		getTextWidget().setEditable(!readOnly);

		createMarginPainter();

		configureHardWrap();

		final SourceViewerDecorationSupport support = configureAnnotationPreferences();
		if (isEditable(sourceViewer)) {
			final IHandlerActivation handlerActivation = installQuickFixActionHandler();
			getTextWidget().addDisposeListener(new DisposeListener() {

				public void widgetDisposed(DisposeEvent e) {
					getHandlerService().deactivateHandler(handlerActivation);
				}
			});
		}

		Document document = new Document(initialText);

		sourceViewer.configure(new TextSourceViewerConfiguration(EditorsUI
				.getPreferenceStore()) {

			protected Map getHyperlinkDetectorTargets(ISourceViewer targetViewer) {
				return getHyperlinkTargets();
			}

			@Override
			public IReconciler getReconciler(ISourceViewer viewer) {
				if (!isEditable(viewer))
					return null;
				return super.getReconciler(sourceViewer);
			}

			public IContentAssistant getContentAssistant(ISourceViewer viewer) {
				if (!viewer.isEditable())
					return null;
				IContentAssistant assistant = createContentAssistant(viewer);
				// Add content assist proposal handler if assistant exists
				if (assistant != null) {
					final IHandlerActivation activation = installContentAssistActionHandler();
					viewer.getTextWidget().addDisposeListener(
							new DisposeListener() {

								public void widgetDisposed(DisposeEvent e) {
									getHandlerService().deactivateHandler(
											activation);
								}
							});
				}
				return assistant;
			}

		});
		sourceViewer.setDocument(document, annotationModel);

		configureContextMenu();

		getTextWidget().addDisposeListener(new DisposeListener() {
			public void widgetDisposed(DisposeEvent disposeEvent) {
				support.uninstall();
			}
		});
	}

	private boolean isEditable(ISourceViewer viewer) {
		return viewer != null && viewer.getTextWidget().getEditable();
	}

	private void configureHardWrap() {
		if (shouldHardWrap()) {
			if (hardWrapModifyListener == null) {
				final StyledText textWidget = getTextWidget();

				hardWrapModifyListener = new ModifyListener() {

					private boolean active = true;

					public void modifyText(ModifyEvent e) {
						if (!active)
							return;
						String lineDelimiter = textWidget.getLineDelimiter();
						List<WrapEdit> wrapEdits = calculateWrapEdits(
								textWidget.getText(), MAX_LINE_WIDTH,
								lineDelimiter);
						// Prevent infinite loop because replaceTextRange causes a ModifyEvent
						active = false;
						textWidget.setRedraw(false);
						try {
							for (WrapEdit wrapEdit : wrapEdits)
								textWidget.replaceTextRange(wrapEdit.getStart(), wrapEdit.getLength(),
										wrapEdit.getReplacement());
						} finally {
							textWidget.setRedraw(true);
							active = true;
						}
					}
				};
				textWidget.addModifyListener(hardWrapModifyListener);
			}
		} else if (hardWrapModifyListener != null) {
			getTextWidget().removeModifyListener(hardWrapModifyListener);
			hardWrapModifyListener = null;
		}
	}

	private void configureContextMenu() {
		final boolean editable = isEditable(sourceViewer);
		final TextViewerAction cutAction;
		final TextViewerAction undoAction;
		final TextViewerAction redoAction;
		final TextViewerAction pasteAction;
		if (editable) {
			cutAction = new TextViewerAction(sourceViewer,
					ITextOperationTarget.CUT);
			cutAction.setText(UIText.SpellCheckingMessageArea_cut);
			cutAction
					.setActionDefinitionId(IWorkbenchCommandConstants.EDIT_CUT);

			undoAction = new TextViewerAction(sourceViewer,
					ITextOperationTarget.UNDO);
			undoAction.setText(UIText.SpellcheckableMessageArea_undo);
			undoAction
					.setActionDefinitionId(IWorkbenchCommandConstants.EDIT_UNDO);

			redoAction = new TextViewerAction(sourceViewer,
					ITextOperationTarget.REDO);
			redoAction.setText(UIText.SpellcheckableMessageArea_redo);
			redoAction
					.setActionDefinitionId(IWorkbenchCommandConstants.EDIT_REDO);

			pasteAction = new TextViewerAction(sourceViewer,
					ITextOperationTarget.PASTE);
			pasteAction.setText(UIText.SpellCheckingMessageArea_paste);
			pasteAction
					.setActionDefinitionId(IWorkbenchCommandConstants.EDIT_PASTE);
		} else {
			cutAction = null;
			undoAction = null;
			redoAction = null;
			pasteAction = null;
		}

		final TextViewerAction copyAction = new TextViewerAction(sourceViewer,
				ITextOperationTarget.COPY);
		copyAction.setText(UIText.SpellCheckingMessageArea_copy);
		copyAction.setActionDefinitionId(IWorkbenchCommandConstants.EDIT_COPY);

		final TextViewerAction selectAllAction = new TextViewerAction(
				sourceViewer, ITextOperationTarget.SELECT_ALL);
		selectAllAction.setText(UIText.SpellCheckingMessageArea_selectAll);
		selectAllAction
				.setActionDefinitionId(IWorkbenchCommandConstants.EDIT_SELECT_ALL);

		final TextEditorPropertyAction showWhitespaceAction = new TextEditorPropertyAction(
				UIText.SpellcheckableMessageArea_showWhitespace,
				sourceViewer,
				AbstractTextEditor.PREFERENCE_SHOW_WHITESPACE_CHARACTERS) {

			private IPainter whitespaceCharPainter;

			@Override
			protected void toggleState(boolean checked) {
				if (checked)
					installPainter();
				else
					uninstallPainter();
			}

			/**
			 * Installs the painter on the viewer.
			 */
			private void installPainter() {
				Assert.isTrue(whitespaceCharPainter == null);
				ITextViewer v = getTextViewer();
				if (v instanceof ITextViewerExtension2) {
					whitespaceCharPainter = new WhitespaceCharacterPainter(v);
					((ITextViewerExtension2) v).addPainter(whitespaceCharPainter);
				}
			}

			/**
			 * Remove the painter from the viewer.
			 */
			private void uninstallPainter() {
				if (whitespaceCharPainter == null)
					return;
				ITextViewer v = getTextViewer();
				if (v instanceof ITextViewerExtension2)
					((ITextViewerExtension2) v)
							.removePainter(whitespaceCharPainter);
				whitespaceCharPainter.deactivate(true);
				whitespaceCharPainter = null;
			}
		};

		MenuManager contextMenu = new MenuManager();
		if (cutAction != null)
			contextMenu.add(cutAction);
		contextMenu.add(copyAction);
		if (pasteAction != null)
			contextMenu.add(pasteAction);
		contextMenu.add(selectAllAction);
		if (undoAction != null)
			contextMenu.add(undoAction);
		if (redoAction != null)
			contextMenu.add(redoAction);
		contextMenu.add(new Separator());
		contextMenu.add(showWhitespaceAction);
		contextMenu.add(new Separator());

		if (editable) {
			final SubMenuManager quickFixMenu = new SubMenuManager(contextMenu);
			quickFixMenu.setVisible(true);
			quickFixMenu.addMenuListener(new IMenuListener() {
				public void menuAboutToShow(IMenuManager manager) {
					quickFixMenu.removeAll();
					addProposals(quickFixMenu);
				}
			});
		}

		final StyledText textWidget = getTextWidget();
		textWidget.setMenu(contextMenu.createContextMenu(textWidget));

		textWidget.addFocusListener(new FocusListener() {

			private IHandlerActivation cutHandlerActivation;
			private IHandlerActivation copyHandlerActivation;
			private IHandlerActivation pasteHandlerActivation;
			private IHandlerActivation selectAllHandlerActivation;
			private IHandlerActivation undoHandlerActivation;
			private IHandlerActivation redoHandlerActivation;

			public void focusGained(FocusEvent e) {
				IHandlerService service = (IHandlerService) PlatformUI
						.getWorkbench().getService(IHandlerService.class);
				if (cutAction != null) {
					cutAction.update();
					cutHandlerActivation = service.activateHandler(
							IWorkbenchCommandConstants.EDIT_CUT,
							new ActionHandler(cutAction),
							new ActiveShellExpression(getParent().getShell()));
				}
				copyAction.update();

				copyHandlerActivation = service.activateHandler(
						IWorkbenchCommandConstants.EDIT_COPY,
						new ActionHandler(copyAction),
						new ActiveShellExpression(getParent().getShell()));
				if (pasteAction != null)
					this.pasteHandlerActivation = service.activateHandler(
							IWorkbenchCommandConstants.EDIT_PASTE,
							new ActionHandler(pasteAction),
							new ActiveShellExpression(getParent().getShell()));
				selectAllHandlerActivation = service.activateHandler(
						IWorkbenchCommandConstants.EDIT_SELECT_ALL,
						new ActionHandler(selectAllAction),
						new ActiveShellExpression(getParent().getShell()));
				if (undoAction != null)
					undoHandlerActivation = service.activateHandler(
							IWorkbenchCommandConstants.EDIT_UNDO,
							new ActionHandler(undoAction),
							new ActiveShellExpression(getParent().getShell()));
				if (redoAction != null)
					redoHandlerActivation = service.activateHandler(
							IWorkbenchCommandConstants.EDIT_REDO,
							new ActionHandler(redoAction),
							new ActiveShellExpression(getParent().getShell()));
			}

			public void focusLost(FocusEvent e) {
				IHandlerService service = (IHandlerService) PlatformUI.getWorkbench().getService(IHandlerService.class);

				if (cutHandlerActivation != null)
					service.deactivateHandler(cutHandlerActivation);

				if (copyHandlerActivation != null)
					service.deactivateHandler(copyHandlerActivation);

				if (pasteHandlerActivation != null)
					service.deactivateHandler(pasteHandlerActivation);

				if (selectAllHandlerActivation != null)
					service.deactivateHandler(selectAllHandlerActivation);

				if (undoHandlerActivation != null)
					service.deactivateHandler(undoHandlerActivation);

				if (redoHandlerActivation != null)
					service.deactivateHandler(redoHandlerActivation);
			}

		});

        sourceViewer.addSelectionChangedListener(new ISelectionChangedListener() {

					public void selectionChanged(SelectionChangedEvent event) {
						if (cutAction != null)
							cutAction.update();
						copyAction.update();
					}

				});

		if (editable)
			sourceViewer.addTextListener(new ITextListener() {

				public void textChanged(TextEvent event) {
					if (undoAction != null)
						undoAction.update();
					if (redoAction != null)
						redoAction.update();
				}
			});

		textWidget.addDisposeListener(new DisposeListener() {
			public void widgetDisposed(DisposeEvent disposeEvent) {
				showWhitespaceAction.dispose();
			}
		});
	}

	private void addProposals(final SubMenuManager quickFixMenu) {
		IAnnotationModel sourceModel = sourceViewer.getAnnotationModel();
		Iterator annotationIterator = sourceModel.getAnnotationIterator();
		while (annotationIterator.hasNext()) {
			Annotation annotation = (Annotation) annotationIterator.next();
			boolean isDeleted = annotation.isMarkedDeleted();
			boolean isIncluded = includes(sourceModel.getPosition(annotation),
					getTextWidget().getCaretOffset());
			boolean isFixable = sourceViewer.getQuickAssistAssistant().canFix(
					annotation);
			if (!isDeleted && isIncluded && isFixable) {
				IQuickAssistProcessor processor = sourceViewer
				.getQuickAssistAssistant()
				.getQuickAssistProcessor();
				IQuickAssistInvocationContext context = sourceViewer
				.getQuickAssistInvocationContext();
				ICompletionProposal[] proposals = processor
				.computeQuickAssistProposals(context);

				for (ICompletionProposal proposal : proposals)
					quickFixMenu.add(createQuickFixAction(proposal));
			}
		}
	}

	private boolean includes(Position position, int caretOffset) {
		return position.includes(caretOffset)
		|| (position.offset + position.length) == caretOffset;
	}

	private IAction createQuickFixAction(final ICompletionProposal proposal) {
		return new Action(proposal.getDisplayString()) {

			public void run() {
				proposal.apply(sourceViewer.getDocument());
			}

			public ImageDescriptor getImageDescriptor() {
				Image image = proposal.getImage();
				if (image != null)
					return ImageDescriptor.createFromImage(image);
				return null;
			}
		};
	}

	/**
	 * Return <code>IHandlerService</code>. The default implementation uses the
	 * workbench window's service locator. Subclasses may override to access the
	 * service by using a local service locator.
	 *
	 * @return <code>IHandlerService</code> using the workbench window's service
	 *         locator. Can be <code>null</code> if the service could not be
	 *         found.
	 */
	protected IHandlerService getHandlerService() {
		return (IHandlerService) PlatformUI.getWorkbench().getService(
				IHandlerService.class);
	}

	private SourceViewerDecorationSupport configureAnnotationPreferences() {
		ISharedTextColors textColors = EditorsUI.getSharedTextColors();
		IAnnotationAccess annotationAccess = new DefaultMarkerAnnotationAccess();
		final SourceViewerDecorationSupport support = new SourceViewerDecorationSupport(
				sourceViewer, null, annotationAccess, textColors);

		List annotationPreferences = new MarkerAnnotationPreferences()
		.getAnnotationPreferences();
		Iterator e = annotationPreferences.iterator();
		while (e.hasNext())
			support.setAnnotationPreference((AnnotationPreference) e.next());

		support.install(EditorsUI.getPreferenceStore());
		return support;
	}

	/**
	 * Create margin painter and add to source viewer
	 */
	protected void createMarginPainter() {
		MarginPainter marginPainter = new MarginPainter(sourceViewer);
		marginPainter.setMarginRulerColumn(MAX_LINE_WIDTH);
		marginPainter.setMarginRulerColor(Display.getDefault().getSystemColor(
				SWT.COLOR_GRAY));
		sourceViewer.addPainter(marginPainter);
	}

	private int getCharWidth() {
		GC gc = new GC(getTextWidget());
		int charWidth = gc.getFontMetrics().getAverageCharWidth();
		gc.dispose();
		return charWidth;
	}

	private int getLineHeight() {
		return getTextWidget().getLineHeight();
	}

	/**
	 * @return if the commit message should be hard-wrapped (preference)
	 */
	private static boolean shouldHardWrap() {
		return Activator.getDefault().getPreferenceStore()
				.getBoolean(UIPreferences.COMMIT_DIALOG_HARD_WRAP_MESSAGE);
	}

	/**
	 * @return widget
	 */
	public StyledText getTextWidget() {
		return sourceViewer.getTextWidget();
	}

	private IHandlerActivation installQuickFixActionHandler() {
		ActionHandler handler = createQuickFixActionHandler(sourceViewer);
		return addHandler(handler);
	}

	private IHandlerActivation installContentAssistActionHandler() {
		ActionHandler handler = createContentAssistActionHandler(sourceViewer);
		return addHandler(handler);
	}

	private IHandlerActivation addHandler(ActionHandler handler) {
		ActiveShellExpression expression = new ActiveShellExpression(
				sourceViewer.getTextWidget().getShell());
		return getHandlerService().activateHandler(
				handler.getAction().getActionDefinitionId(), handler,
				expression);
	}

	private ActionHandler createQuickFixActionHandler(
			final ITextOperationTarget textOperationTarget) {
		Action quickFixAction = new Action() {

			public void run() {
				textOperationTarget.doOperation(ISourceViewer.QUICK_ASSIST);
			}
		};
		quickFixAction
		.setActionDefinitionId(ITextEditorActionDefinitionIds.QUICK_ASSIST);
		return new ActionHandler(quickFixAction);
	}

	private ActionHandler createContentAssistActionHandler(
			final ITextOperationTarget textOperationTarget) {
		Action proposalAction = new Action() {
			public void run() {
				if (textOperationTarget
						.canDoOperation(ISourceViewer.CONTENTASSIST_PROPOSALS)
						&& getTextWidget().isFocusControl())
					textOperationTarget
							.doOperation(ISourceViewer.CONTENTASSIST_PROPOSALS);
			}
		};
		proposalAction
				.setActionDefinitionId(ITextEditorActionDefinitionIds.CONTENT_ASSIST_PROPOSALS);
		return new ActionHandler(proposalAction);
	}

	/**
	 * Return the commit message, converting platform-specific line endings.
	 *
	 * @return commit message
	 */
	public String getCommitMessage() {
		String text = getText();
		return Utils.normalizeLineEndings(text);
	}

	/**
	 * Reconfigure this widget if a preference has changed.
	 */
	public void reconfigure() {
		configureHardWrap();
	}

	/**
	 * Get hyperlink targets
	 *
	 * @return map of targets
	 */
	protected Map<String, IAdaptable> getHyperlinkTargets() {
		return Collections.singletonMap("org.eclipse.ui.DefaultTextEditor", //$NON-NLS-1$
				getDefaultTarget());
	}

	/**
	 * Create content assistant
	 *
	 * @param viewer
	 * @return content assistant
	 */
	protected IContentAssistant createContentAssistant(ISourceViewer viewer) {
		return null;
	}

	/**
	 * Get default target for hyperlink presenter
	 *
	 * @return target
	 */
	protected IAdaptable getDefaultTarget() {
		return null;
	}

	/**
	 * @return text
	 */
	public String getText() {
		return getTextWidget().getText();
	}

	/**
	 * @return document
	 */
	public IDocument getDocument() {
		return sourceViewer.getDocument();
	}

	/**
	 * @param text
	 */
	public void setText(String text) {
		if (text != null)
			getTextWidget().setText(text);
	}

	/**
	 *
	 */
	public boolean setFocus() {
		return getTextWidget().setFocus();
	}

	/**
	 * Calculate a list of {@link WrapEdit} which can be applied to the text to
	 * get a new text that is wrapped at word boundaries. Existing line breaks
	 * are left alone (text is not reflowed).
	 *
	 * @param text
	 *            the text to calculate the wrap edits for
	 * @param maxLineLength
	 *            the maximum line length
	 * @param lineDelimiter
	 *            line delimiter used in text and for wrapping
	 * @return a list of {@link WrapEdit} objects which specify how the text
	 *         should be edited to obtain the wrapped text. Offsets of later
	 *         edits are already adjusted for the fact that wrapping a line may
	 *         shift the text backwards. So the list can just be iterated and
	 *         each edit applied in order.
	 */
	public static List<WrapEdit> calculateWrapEdits(final String text, final int maxLineLength, final String lineDelimiter) {
		List<WrapEdit> wrapEdits = new LinkedList<WrapEdit>();

		int offset = 0;
		final int lineDelimiterLength = lineDelimiter.length();
		final int spaceLength = 1;

		boolean lastChunkWasWrapped = false;
		int lastChunkWrappedWordLength = 0;

		String[] chunks = text.split(lineDelimiter, -1);
		for (int chunkIndex = 0; chunkIndex < chunks.length; chunkIndex++) {
			String chunk = chunks[chunkIndex];

			String[] words = chunk.split(" ", -1); //$NON-NLS-1$
			int lineLength = 0;

			for (int wordIndex = 0; wordIndex < words.length; wordIndex++) {
				String word = words[wordIndex];
				int wordLength = word.length();

				if (wordIndex == 0 && lastChunkWasWrapped) {
					if (wordLength != 0 && STARTS_WITH_WORD.matcher(word).matches()) {
						wrapEdits.add(new WrapEdit(offset - lineDelimiterLength, lineDelimiterLength, " ")); //$NON-NLS-1$
						lineLength += lastChunkWrappedWordLength + spaceLength;
						/* adjust for join edit above */
						offset += (spaceLength - lineDelimiterLength);
					}
					lastChunkWasWrapped = false;
				}

				int newLineLength = lineLength + wordLength + spaceLength;
				if (newLineLength > maxLineLength) {
					/* don't break before a single long word */
					if (lineLength != 0) {
						wrapEdits.add(new WrapEdit(offset, spaceLength, lineDelimiter));
						/* adjust for the shifting of text after the edit is applied */
						offset += lineDelimiterLength;
						lastChunkWasWrapped = true;
						lastChunkWrappedWordLength = wordLength;
					}
					lineLength = 0;
				} else if (wordIndex != 0) {
					lineLength += spaceLength;
					offset += spaceLength;
				}
				offset += wordLength;
				lineLength += wordLength;
			}

			if (chunkIndex != chunks.length - 1)
				offset += lineDelimiterLength;
		}

		return wrapEdits;
	}

	/**
	 * Edit for replacing a text range with another text to wrap or join lines.
	 */
	public static class WrapEdit {
		private final int start;
		private final int length;
		private final String replacement;

		/**
		 * @param start see {@link #getStart()}
		 * @param length see {@link #getLength()}
		 * @param replacement see {@link #getReplacement()}
		 */
		public WrapEdit(int start, int length, String replacement) {
			this.start = start;
			this.length = length;
			this.replacement = replacement;
		}

		/**
		 * @return character offset of where the edit should be applied on the
		 *         text
		 */
		public int getStart() {
			return start;
		}

		/**
		 * @return number of characters which should be replaced by the
		 *         replacement text
		 */
		public int getLength() {
			return length;
		}

		/**
		 * @return the text which replaces the edit range
		 */
		public String getReplacement() {
			return replacement;
		}
	}
}
