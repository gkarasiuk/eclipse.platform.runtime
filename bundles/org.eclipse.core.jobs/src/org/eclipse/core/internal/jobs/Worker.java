/*******************************************************************************
 *  Copyright (c) 2003, 2010 IBM Corporation and others.
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 * 
 *  Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.core.internal.jobs;

import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.osgi.util.NLS;

/**
 * A worker thread processes jobs supplied to it by the worker pool.  When
 * the worker pool gives it a null job, the worker dies.
 */
public class Worker extends Thread {
	//worker number used for debugging purposes only
	private static int nextWorkerNumber = 0;
	private volatile InternalJob currentJob;
	private final WorkerPool pool;

	public Worker(WorkerPool pool) {
		super("Worker-" + nextWorkerNumber++); //$NON-NLS-1$
		this.pool = pool;
		//set the context loader to avoid leaking the current context loader
		//for the thread that spawns this worker (bug 98376)
		setContextClassLoader(pool.defaultContextLoader);
	}

	/**
	 * Returns the currently running job, or null if none.
	 */
	public Job currentJob() {
		return (Job) currentJob;
	}

	private IStatus handleException(InternalJob job, Throwable t) {
		String message = NLS.bind(JobMessages.jobs_internalError, job.getName());
		return new Status(IStatus.ERROR, JobManager.PI_JOBS, JobManager.PLUGIN_ERROR, message, t);
	}

	public void run() {
		setPriority(Thread.NORM_PRIORITY);
		try {
			while ((currentJob = pool.startJob(this)) != null) {
				currentJob.setThread(this);
				IStatus result = Status.OK_STATUS;
				try {
					result = currentJob.run(currentJob.getProgressMonitor());
				} catch (OperationCanceledException e) {
					result = Status.CANCEL_STATUS;
				} catch (Exception e) {
					result = handleException(currentJob, e);
				} catch (ThreadDeath e) {
					//must not consume thread death
					result = handleException(currentJob, e);
					throw e;
				} catch (Error e) {
					result = handleException(currentJob, e);
				} finally {
					//clear interrupted state for this thread
					Thread.interrupted();
					//result must not be null
					if (result == null)
						result = handleException(currentJob, new NullPointerException());
					pool.endJob(currentJob, result);
					currentJob = null;
					//reset thread priority in case job changed it
					setPriority(Thread.NORM_PRIORITY);
				}
			}
		} catch (Throwable t) {
			t.printStackTrace();
		} finally {
			currentJob = null;
			pool.endWorker(this);
		}
	}
}
