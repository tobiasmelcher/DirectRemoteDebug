package org.eclipse.cdt.launch.remote.direct;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.cdt.dsf.concurrent.DataRequestMonitor;
import org.eclipse.cdt.dsf.concurrent.RequestMonitor;
import org.eclipse.cdt.dsf.debug.service.command.ICommand;
import org.eclipse.cdt.dsf.gdb.internal.GdbPlugin;
import org.eclipse.cdt.dsf.gdb.service.command.GDBControl;
import org.eclipse.cdt.dsf.mi.service.IMICommandControl;
import org.eclipse.cdt.dsf.mi.service.command.CommandFactory;
import org.eclipse.cdt.dsf.mi.service.command.output.MIConsoleStreamOutput;
import org.eclipse.cdt.dsf.mi.service.command.output.MIInfo;
import org.eclipse.cdt.dsf.mi.service.command.output.MIOOBRecord;
import org.eclipse.cdt.dsf.mi.service.command.output.MIOutput;
import org.eclipse.cdt.dsf.mi.service.command.output.MITargetStreamOutput;
import org.eclipse.cdt.dsf.service.DsfServicesTracker;
import org.eclipse.cdt.dsf.service.DsfSession;
import org.eclipse.cdt.ui.ICEditor;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.DoubleClickEvent;
import org.eclipse.jface.viewers.IDoubleClickListener;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.IWorkbenchActionConstants;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.DrillDownAdapter;
import org.eclipse.ui.part.ViewPart;

@SuppressWarnings("restriction")
public class VariableView extends ViewPart {
	private TreeViewer viewer;
	private DrillDownAdapter drillDownAdapter;
	private Action addWatchAction;
	private Action deleteSelectionAction;
	private Action doubleClickAction;
	public static RefreshAction refreshAction;

	public class RefreshAction extends Action {
		private RefreshAction(String text) {
			super(text);
		}

		@Override
		public void run() {
			super.run();
			// logic - if enabled, then refresh triggered after each step
			setChecked(!isChecked());
			if (isChecked()) {
				setText("Auto Refresh Enabled");
			} else {
				setText("Auto Refresh Disabled");
			}
		}

		public void refreshNow() {
			if (refreshAction != null && refreshAction.isChecked() == false)
				return;
			Display.getDefault().asyncExec(new Runnable() {

				@Override
				public void run() {
					TreeItem[] items = viewer.getTree().getItems();
					List<TreeParent> nodes = new ArrayList<VariableView.TreeParent>();
					getAllGdbNodes(items, nodes);
					invisibleRoot.clear();
					for (TreeParent node : nodes) {
						String cmd = node.initialCommandStr;
						if (cmd == null)
							cmd = node.gdbRequest;
						gdbCommand(cmd, null, invisibleRoot, true, true);
					}
				}
			});
		}

		private void getAllGdbNodes(TreeItem[] items, List<TreeParent> result) {
			if (items == null)
				return;
			for (TreeItem item : items) {
				Object data = item.getData();
				if (data instanceof TreeParent) {
					TreeParent tp = (TreeParent) data;
					if (tp.gdbRequest != null)
						result.add(tp);
					// getAllGdbNodes(item.getItems(), result);
				}
			}
		}
	}

	static class TreeObject implements IAdaptable {
		private String name;
		private TreeParent parent;
		public String uniqueId;

		public TreeObject(String name) {
			this.name = name;
		}

		public String getName() {
			return name;
		}

		public void setParent(TreeParent parent) {
			this.parent = parent;
		}

		public TreeParent getParent() {
			return parent;
		}

		public String toString() {
			return getName();
		}

		public <T> T getAdapter(Class<T> key) {
			return null;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((uniqueId == null) ? 0 : uniqueId.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			TreeObject other = (TreeObject) obj;
			if (uniqueId == null) {
				if (other.uniqueId != null)
					return false;
			} else if (!uniqueId.equals(other.uniqueId))
				return false;
			return true;
		}
	}

	static class TreeParent extends TreeObject {
		private ArrayList<Object> children;
		public String gdbRequest;
		public String initialCommandStr;

		public TreeParent(String name, TreeParent parent) {
			super(name);
			children = new ArrayList<Object>();
			setParent(parent);
			if (parent == null)
				uniqueId = "0000000";
			else
				uniqueId = createUniqueId(parent);
		}

		private String createUniqueId(TreeParent parent) {
			StringBuilder result = new StringBuilder();
			while (parent != null) {
				result.append(parent.children.size()).append("_");
				parent = parent.getParent();
			}
			return result.toString();
		}

		public void clear() {
			children.clear();
		}

		public void addChild(TreeObject child) {
			children.add(child);
			child.setParent(this);
		}

		public void removeChild(TreeObject child) {
			children.remove(child);
			child.setParent(null);
		}

		public TreeObject[] getChildren() {
			return (TreeObject[]) children.toArray(new TreeObject[children.size()]);
		}

		public boolean hasChildren() {
			return children.size() > 0;
		}

		public void setGdbRequest(String commandStr) {
			gdbRequest = commandStr;
		}

		public void setInitialGdbRequest(String initialCommandStr) {
			this.initialCommandStr = initialCommandStr;
		}

		@Override
		public boolean equals(Object obj) {
			return super.equals(obj);
		}

		@Override
		public int hashCode() {
			return super.hashCode();
		}
	}

	private TreeParent invisibleRoot;

	class ViewContentProvider implements ITreeContentProvider {

		public Object[] getElements(Object parent) {
			if (parent.equals(getViewSite())) {
				if (invisibleRoot == null)
					initialize();
				return getChildren(invisibleRoot);
			}
			return getChildren(parent);
		}

		public Object getParent(Object child) {
			if (child instanceof TreeObject) {
				return ((TreeObject) child).getParent();
			}
			return null;
		}

		public Object[] getChildren(Object parent) {
			if (parent instanceof TreeParent) {
				return ((TreeParent) parent).getChildren();
			}
			return new Object[0];
		}

		public boolean hasChildren(Object parent) {
			if (parent instanceof TreeParent)
				return ((TreeParent) parent).hasChildren();
			return false;
		}

		private void initialize() {
			invisibleRoot = new TreeParent("", null);
		}
	}

	private static class ViewLabelProvider extends LabelProvider {

		public String getText(Object obj) {
			return obj.toString();
		}

		public Image getImage(Object obj) {
			String imageKey = ISharedImages.IMG_OBJ_ELEMENT;
			if (obj instanceof TreeParent)
				imageKey = ISharedImages.IMG_OBJ_FOLDER;
			return PlatformUI.getWorkbench().getSharedImages().getImage(imageKey);
		}
	}

	public VariableView() {
	}

	public void createPartControl(Composite parent) {
		viewer = new TreeViewer(parent, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL);
		drillDownAdapter = new DrillDownAdapter(viewer);

		viewer.setContentProvider(new ViewContentProvider());
		viewer.setInput(getViewSite());
		viewer.setLabelProvider(new ViewLabelProvider());
		getSite().setSelectionProvider(viewer);
		makeActions();
		hookContextMenu();
		hookDoubleClickAction();
		contributeToActionBars();
	}

	private void hookContextMenu() {
		MenuManager menuMgr = new MenuManager("#PopupMenu");
		menuMgr.setRemoveAllWhenShown(true);
		menuMgr.addMenuListener(new IMenuListener() {
			public void menuAboutToShow(IMenuManager manager) {
				VariableView.this.fillContextMenu(manager);
			}
		});
		Menu menu = menuMgr.createContextMenu(viewer.getControl());
		viewer.getControl().setMenu(menu);
		getSite().registerContextMenu(menuMgr, viewer);
	}

	private void contributeToActionBars() {
		IActionBars bars = getViewSite().getActionBars();
		fillLocalPullDown(bars.getMenuManager());
		fillLocalToolBar(bars.getToolBarManager());
	}

	private void fillLocalPullDown(IMenuManager manager) {
		manager.add(addWatchAction);
		manager.add(new Separator());
	}

	private void fillContextMenu(IMenuManager manager) {
		manager.add(addWatchAction);
		manager.add(deleteSelectionAction);
		manager.add(doubleClickAction);
		manager.add(refreshAction);
		manager.add(new Separator());
		drillDownAdapter.addNavigationActions(manager);
		// Other plug-ins can contribute there actions here
		manager.add(new Separator(IWorkbenchActionConstants.MB_ADDITIONS));
	}

	private void fillLocalToolBar(IToolBarManager manager) {
		manager.add(addWatchAction);
		manager.add(refreshAction);
		manager.add(new Separator());
		drillDownAdapter.addNavigationActions(manager);
	}

	private void makeActions() {
		refreshAction = new RefreshAction("Auto Refresh Disabled");
		deleteSelectionAction = new Action() {
			@Override
			public void run() {
				super.run();
				TreeItem[] items = viewer.getTree().getSelection();
				for (TreeItem item : items) {
					Object data = item.getData();
					if (data instanceof TreeObject)
						invisibleRoot.removeChild((TreeObject) data);
				}
				IViewSite viewSite = getViewSite();
				viewer.refresh(viewSite);
			}
		};
		deleteSelectionAction.setText("Delete");
		deleteSelectionAction.setToolTipText("Delete");

		addWatchAction = new Action() {
			public void run() {
				IEditorPart part = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage()
						.getActiveEditor();
				if (part instanceof ICEditor == false) {
					return;
				}
				ISelection sel = part.getSite().getSelectionProvider().getSelection();
				if (sel instanceof ITextSelection == false) {
					return;
				}
				ITextSelection text = (ITextSelection) sel;
				String str = text.getText();
				if (str == null || str.trim().length() == 0 || str.contains("\n")) {
					return;
				}
				String commandStr = "p " + str;
				gdbCommand(commandStr, null, invisibleRoot, true, false);
			}
		};
		addWatchAction.setText("Add Watch");
		addWatchAction.setToolTipText("Add Watch Expression");
		addWatchAction.setImageDescriptor(
				PlatformUI.getWorkbench().getSharedImages().getImageDescriptor(ISharedImages.IMG_OBJS_INFO_TSK));

		doubleClickAction = new Action() {
			public void run() {
				ISelection selection = viewer.getSelection();
				Object obj = ((IStructuredSelection) selection).getFirstElement();
				if (obj instanceof TreeParent) {
					TreeParent tp = (TreeParent) obj;
					String name = tp.getName();
					if (name.contains("std::map")) {
						int ind = name.indexOf("=");
						if (ind > 0) {
							String variableValue = name.substring(ind + 1).trim();
							String variableName = name.substring(0, ind).trim();
							mapGdbCommand(variableName, variableValue, tp);
							return;
						}
					}
					if (tp.hasChildren() == false) {
						int ind = name.indexOf("=");
						if (ind > 0) {
							String variableValue = name.substring(ind + 1).trim();
							String variableName = name.substring(0, ind).trim();
							if (variableValue.contains("std::deque") || variableValue.contains("std::vector")
									|| variableValue.contains("std::set")) {
								dequeGdbCommand(variableValue, tp);
								return;
							}
							String[] dollarVariableAndClassName = getDollarVariableAndClassNameFromFirstParent(tp);
							if (dollarVariableAndClassName != null) {
								String cmd = "p ((" + dollarVariableAndClassName[1] + ")"
										+ dollarVariableAndClassName[0] + ")." + variableName;
								gdbCommand(cmd, null, tp, true, false);
								return;
							}
						}
					}
				}
			}
		};
		doubleClickAction.setText("expand node");
	}

	private void mapGdbCommand(String variableName, String variableValue, TreeParent tp) {
		int mapIdx = variableName.indexOf("map<");
		if (mapIdx < 0)
			return;
		mapIdx += "map<".length();
		String keyType = getMapType(variableName, mapIdx);
		String valueType = getMapType(variableName, mapIdx + keyType.length() + 1);
		Pattern p = Pattern.compile("\\[(0x[a-zA-Z0-9]+)\\] = (0x[a-zA-Z0-9]+)");
		Matcher matcher = p.matcher(variableValue);
		while (matcher.find()) {
			String key = matcher.group(1);
			String value = matcher.group(2);
			TreeParent node = new TreeParent(key + "=" + value, tp);
			tp.addChild(node);
			String cmd = "p *((" + keyType + ") " + key + ")";
			gdbCommand(cmd, null, node, false, true);
			cmd = "p *((" + valueType + ") " + value + ")";
			gdbCommand(cmd, null, node, false, true);
		}
		IViewSite viewSite = getViewSite();
		viewer.refresh(viewSite);
	}

	private String getMapType(String string, int idx) {
		int startIdx = idx;
		int doContinue = 0;
		while (true) {
			char ch = string.charAt(idx);
			if (ch == '<') {
				doContinue++;
			} else if (ch == '>') {
				doContinue--;
			} else if (ch == ',' && doContinue == 0) {
				String sub = string.substring(startIdx, idx);
				return sub;
			}
			idx++;
		}
	}

	private String[] getDollarVariableAndClassNameFromFirstParent(TreeParent tp) {
		Pattern p = Pattern.compile("(\\$\\d+) = \\(([a-zA-Z0-9]+)\\)");
		while (tp != null) {
			tp = tp.getParent();
			if (tp == null)
				return null;
			String name = tp.getName();
			Matcher matcher = p.matcher(name);
			if (matcher.find()) {
				String group = matcher.group(1);
				String className = matcher.group(2);
				return new String[] { group, className };
			}
		}
		return null;
	}

	private void dequeGdbCommand(String variableValue, TreeParent tp) {
		Matcher matcher = Pattern.compile("<([^>]*)>").matcher(variableValue);
		if (matcher.find()) {
			String prelimType = matcher.group(1);
			matcher = Pattern.compile("(0x[0-9a-zA-Z]+)").matcher(variableValue);
			List<String> hexValues = new ArrayList<String>();
			while (matcher.find()) {
				String hexValue = matcher.group(1);
				hexValues.add(hexValue);
			}
			for (String hv : hexValues) {
				TreeParent p = new TreeParent(hv, tp);
				tp.addChild(p);
				String cmd = "p *((" + prelimType + ") " + hv + ")";
				gdbCommand(cmd, null, p, false, true);
			}
			IViewSite viewSite = getViewSite();
			viewer.refresh(viewSite);
		}
	}

	private void hookDoubleClickAction() {
		viewer.addDoubleClickListener(new IDoubleClickListener() {
			public void doubleClick(DoubleClickEvent event) {
				doubleClickAction.run();
			}
		});
	}

	private void showMessage(String message) {
		MessageDialog.openInformation(viewer.getControl().getShell(), "VariableView", message);
	}

	static Pattern withNumElementsPattern = Pattern.compile("with (\\d+) elements");

	/**
	 * Passing the focus request to the viewer's control.
	 */
	public void setFocus() {
		viewer.getControl().setFocus();
	}

	private void gdbCommand(final String commandStr, String initialCommandStr, final TreeParent treeParentNode,
			final boolean refreshTree, boolean waitForResult) {
		GDBControl control = DirectRemoteServicesFactory.lastCreatedCommandControl;
		DsfSession session = DirectRemoteServicesFactory.lastDsfSession;
		if (control == null)
			return;
		// TODO do not create an own tracker - find the existing one
		DsfServicesTracker tracker = new DsfServicesTracker(GdbPlugin.getBundleContext(), session.getId());
		CommandFactory commandFactory = tracker.getService(IMICommandControl.class).getCommandFactory();
		ICommand<MIInfo> command = commandFactory.createMIInterpreterExec(control.getContext(), "console", commandStr);
		Drm drm = new Drm(session.getExecutor(), null, treeParentNode, refreshTree, commandStr, initialCommandStr);
		control.queueCommand(command, drm);
		if (waitForResult) {
			while (drm.finished == false) {
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
	}

	private class Drm extends DataRequestMonitor<MIInfo> {
		private TreeParent treeParentNode;
		private boolean refreshTree;
		private String commandStr;
		public boolean finished = false;
		private String initialCommandStr;

		public Drm(Executor executor, RequestMonitor parentRequestMonitor, TreeParent treeParentNode,
				boolean refreshTree, String commandStr, String initialCommandStr) {
			super(executor, parentRequestMonitor);
			this.treeParentNode = treeParentNode;
			this.refreshTree = refreshTree;
			this.commandStr = commandStr;
			this.initialCommandStr = initialCommandStr;
		}

		@Override
		protected void handleError() {
			super.handleError();
			finished = true;
		}

		@Override
		protected void handleFailure() {
			super.handleFailure();
			finished = true;
		}

		@Override
		protected void handleErrorOrWarning() {
			super.handleErrorOrWarning();
			finished = true;
		}

		@Override
		protected void handleRejectedExecutionException() {
			super.handleRejectedExecutionException();
			finished = true;
		}

		@Override
		protected void handleSuccess() {
			super.handleSuccess();
			MIInfo data = getData();
			MIOutput output = data.getMIOutput();
			if (output == null)
				return;
			MIOOBRecord[] records = output.getMIOOBRecords();
			if (records == null || records.length == 0)
				return;
			boolean doit = false;
			for (int i = 0; i < records.length; i++) {
				MIOOBRecord r = records[i];
				if (r instanceof MITargetStreamOutput) {
					String a = ((MITargetStreamOutput) r).getString().trim();
					if (a.contains(commandStr)) {
						doit = true;
					}
				} else if (r instanceof MIConsoleStreamOutput) {
					MIConsoleStreamOutput m = (MIConsoleStreamOutput) r;
					String a = m.getString().trim();
					if (doit == false)
						continue;
					if (a.length() == 0)
						continue;
					if (containsInNonStrValue(a, '{') <= 0) {
						a = a.substring(a.indexOf("=") + 1);
						a = "p *(" + a + ")";
						gdbCommand(a, commandStr, treeParentNode, refreshTree, false);
					} else {
						// structured value -> consume more records
						TreeParent currentParent = new TreeParent(a, treeParentNode);
						currentParent.setGdbRequest(commandStr);
						currentParent.setInitialGdbRequest(initialCommandStr);
						treeParentNode.addChild(currentParent);

						for (int q = i + 1; q < records.length; q++) {
							m = (MIConsoleStreamOutput) records[q];
							a = m.getString().trim();
							if (a.contains("DbQlAtomicExpression")) {
								System.out.println();
							}
							Matcher matcher = withNumElementsPattern.matcher(a);
							if (matcher.find()) {
								String number = matcher.group(1);
								int num = Integer.parseInt(number);
								if (a.contains("std::map")) {
									if (num > 0) {
										while (true) {
											q++;
											String add = ((MIConsoleStreamOutput) records[q]).getString().trim();
											if (add.contains("}"))
												break;
											a += add;
										}
									}
								} else {
									if (num > 0) {
										for (int w = 1; w < num; w++) {
											String add = ((MIConsoleStreamOutput) records[q + w]).getString();
											a += add;
										}
										q += num - 1;
									}
								}
							}
							if (",".equals(a) || a.length() == 0)
								continue;
							int numClosingBracket = containsInNonStrValue(a, '}');
							if (numClosingBracket > 0) {
								while (numClosingBracket > 0) {
									currentParent = currentParent.getParent();
									numClosingBracket--;
								}
							}
							TreeParent currentChild = null;
							if (containsInNonStrValue(a, '=') > 0) {
								currentChild = new TreeParent(a, currentParent);
								currentParent.addChild(currentChild);
							}
							if (currentChild != null && containsInNonStrValue(a, '{') > 0) {
								currentParent = currentChild;
							}
						}
						if (refreshTree) {
							Display.getDefault().asyncExec(new Runnable() {

								@Override
								public void run() {
									IViewSite viewSite = getViewSite();
									// viewer.setInput(viewSite);
									viewer.refresh(viewSite);
								}
							});
						}
					}
					break;
				}
			}
			finished = true;
		}
	}

	private static int containsInNonStrValue(String value, char matchCh) {
		int result = 0;
		boolean inString = false;
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == '"') {
				inString = !inString;
			}
			if (inString == false && c == matchCh) {
				result++;
			}
		}
		return result;
	}
}
