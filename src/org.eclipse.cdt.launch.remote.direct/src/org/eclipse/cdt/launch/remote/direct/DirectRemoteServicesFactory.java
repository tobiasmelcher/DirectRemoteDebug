package org.eclipse.cdt.launch.remote.direct;

import org.eclipse.cdt.dsf.debug.service.IBreakpoints;
import org.eclipse.cdt.dsf.debug.service.command.ICommand;
import org.eclipse.cdt.dsf.debug.service.command.ICommandControl;
import org.eclipse.cdt.dsf.debug.service.command.ICommandListener;
import org.eclipse.cdt.dsf.debug.service.command.ICommandResult;
import org.eclipse.cdt.dsf.debug.service.command.ICommandToken;
import org.eclipse.cdt.dsf.gdb.service.GDBBreakpoints_7_7;
import org.eclipse.cdt.dsf.gdb.service.GdbDebugServicesFactory;
import org.eclipse.cdt.dsf.gdb.service.command.GDBControl;
import org.eclipse.cdt.dsf.mi.service.IMIBackend;
import org.eclipse.cdt.dsf.mi.service.command.AbstractMIControl;
import org.eclipse.cdt.dsf.mi.service.command.commands.MIExecContinue;
import org.eclipse.cdt.dsf.mi.service.command.commands.MIExecNext;
import org.eclipse.cdt.dsf.mi.service.command.output.MIInfo;
import org.eclipse.cdt.dsf.service.DsfSession;
import org.eclipse.debug.core.ILaunchConfiguration;

public class DirectRemoteServicesFactory extends GdbDebugServicesFactory {

	private DirectRemoteDebugLaunchDelegate directRemoteDelegate = null;
	public static DsfSession lastDsfSession;
	public static GDBControl lastCreatedCommandControl;

	public DirectRemoteServicesFactory(String version, DirectRemoteDebugLaunchDelegate directRemoteDelegate) {
		super(version);
		this.directRemoteDelegate = directRemoteDelegate;
	}

	@Override
	protected ICommandControl createCommandControl(DsfSession session) {
		ICommandControl control = super.createCommandControl(session);
		lastDsfSession = session;
		lastCreatedCommandControl = (GDBControl) control;
		return control;
	}

	protected IMIBackend createBackendGDBService(DsfSession session, ILaunchConfiguration lc) {
		return new GDBDirectRemoteBackend(session, lc, directRemoteDelegate);
	}

	@Override
	protected ICommandControl createCommandControl(DsfSession session, ILaunchConfiguration config) {
		AbstractMIControl gdbControl = (GDBControl) super.createCommandControl(session, config);
		lastDsfSession = session;
		lastCreatedCommandControl = (GDBControl) gdbControl;
		gdbControl.addCommandListener(new ICommandListener() {

			@Override
			public void commandSent(ICommandToken token) {
				String str = token.getCommand().toString();
				System.out.println(str);

			}

			@Override
			public void commandRemoved(ICommandToken token) {
				String str = token.getCommand().toString();
				System.out.println(str);

			}

			@Override
			public void commandQueued(ICommandToken token) {
				String str = token.getCommand().toString();
				System.out.println(str);

			}

			@Override
			public void commandDone(ICommandToken token, ICommandResult result) {
				ICommand<? extends ICommandResult> command = token.getCommand();
				if ((command instanceof MIExecNext || command instanceof MIExecContinue)
						&& VariableView.refreshAction != null) {
					VariableView.refreshAction.refreshNow();
				}
				if (result instanceof MIInfo) {
					if (((MIInfo) result).isExit()) {
						Process remoteProcess = directRemoteDelegate.getRemoteProcess();
						if (remoteProcess != null) {
							remoteProcess.destroy();
						}
					}
				}

			}
		});

		return gdbControl;
	}

	@Override
	protected IBreakpoints createBreakpointService(DsfSession session) {
		if (compareVersionWith(GDB_7_7_VERSION) >= 0) {
			return new GDBBreakpoints_7_7(session) {
				@Override
				public String adjustDebuggerPath(String originalPath) {
					String result = originalPath;
					if (!originalPath.startsWith("/")) { //$NON-NLS-1$
						originalPath = originalPath.replace('\\', '/');
					}
					result = originalPath.substring(originalPath.lastIndexOf('/') + 1);
					return result;
				}
			};
		}
		return super.createBreakpointService(session);
	}

}
