/*******************************************************************************
 * Copyright (c) 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.core.runtime.preferences;

import org.eclipse.core.runtime.IPath;
import org.osgi.service.prefs.Preferences;

/**
 * Clients implement this interface to provide context to a 
 * particular scope. Implementors of this interface are passed to 
 * the <code>IPreferenceManager</code> for use in preference 
 * searching.
 * 
 * @see IPreferenceManager
 * @since 3.0
 */
public interface IScopeContext {
	
	/**
	 * Return the name of the scope that this context is associated with.
	 * 
	 * @return the name of the scope
	 */
	public String getName(); 

	/**
	 * Return the preferences node that contains the preferences for the
	 * given qualifier or <code>null</code> if the node cannot be determined.
	 * <p>
	 * An example of a qualifier in Eclipse 2.1 would be the plug-in identifier that 
	 * the preference is associated with (e.g. the "org.eclipse.core.resources" 
	 * plug-in defines the "description.autobuild" preference).
	 * </p>
	 * @param qualifier a qualifier for the preference name
	 * @return the node containing the plug-in preferences or <code>null</code>
	 * @see IPreferenceManager
	 */
	public Preferences getNode(String qualifier);

	/**
	 * Return a path to a location in the file-system where clients are able
	 * to write files that will have the same sharing/scope properties as
	 * preferences defined in this scope.
	 * <p>
	 * Clients may return <code>null</code> if the location is not known,
	 * is unavailable, or is unapplicable to this scope.
	 * </p>
	 * @return a writable location in the filesystem or <code>null</code>
	 */
	public IPath getLocation();
}