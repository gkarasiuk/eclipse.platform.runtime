/*******************************************************************************
 * Copyright (c) 2000, 2003 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.core.internal.plugins;

import java.util.*;
import java.util.ArrayList;
import org.eclipse.core.internal.runtime.InternalPlatform;
import org.eclipse.core.runtime.*;
import org.osgi.framework.*;

public class PluginRegistry implements IPluginRegistry {
	private IExtensionRegistry extRegistry;

	private HashMap descriptors = new HashMap();	//key is a bundle object, value is a pluginDescriptor. The synchornization is required

	public PluginRegistry() {
		extRegistry = InternalPlatform.getDefault().getRegistry();
		InternalPlatform.getDefault().getBundleContext().addBundleListener(new RegistryListener());
	}

	public IConfigurationElement[] getConfigurationElementsFor(String uniqueId) {
		return extRegistry.getConfigurationElementsFor(uniqueId);
	}

	public IConfigurationElement[] getConfigurationElementsFor(String pluginId, String pointId) {
		return extRegistry.getConfigurationElementsFor(pluginId, pointId);
	}

	public IConfigurationElement[] getConfigurationElementsFor(String pluginId, String pointId, String extensionId) {
		return extRegistry.getConfigurationElementsFor(pluginId, pointId, extensionId);
	}

	public IExtension getExtension(String xptUniqueId, String extUniqueId) {
		return extRegistry.getExtension(xptUniqueId, extUniqueId);
	}

	public IExtension getExtension(String pluginId, String xptSimpleId, String extId) {
		return extRegistry.getExtension(pluginId, xptSimpleId, extId);
	}

	public IExtensionPoint getExtensionPoint(String xptUniqueId) {
		return extRegistry.getExtensionPoint(xptUniqueId);
	}

	public IExtensionPoint getExtensionPoint(String plugin, String xpt) {
		return extRegistry.getExtensionPoint(plugin, xpt);
	}

	public IExtensionPoint[] getExtensionPoints() {
		return extRegistry.getExtensionPoints();
	}

	public IPluginDescriptor getPluginDescriptor(String plugin) {
		Bundle correspondingBundle = InternalPlatform.getDefault().getBundle(plugin);
		if (correspondingBundle == null)
			return null;
		return getPluginDescriptor(correspondingBundle);
	}

	private PluginDescriptor getPluginDescriptor(Bundle bundle) {
		if (InternalPlatform.getDefault().isFragment(bundle)) {
			return null;
		}
		synchronized(descriptors) {
			PluginDescriptor correspondingDescriptor = (PluginDescriptor) descriptors.get(bundle);
			if (bundle != null) {
				// we haven't created a plugin descriptor yet or it was for a different bundle
				if (correspondingDescriptor == null || correspondingDescriptor.getBundle() != bundle) {
					// create a new plugin descriptor and save it for the next time
					correspondingDescriptor = new PluginDescriptor(bundle);
					descriptors.put(bundle, correspondingDescriptor);
				}
				return correspondingDescriptor;
			}
			// if a bundle does not exist, ensure we don't keep a plugin descriptor for it
			if (correspondingDescriptor != null)
				descriptors.remove(bundle);
		}
		return null;
	}
	
	public IPluginDescriptor[] getPluginDescriptors(String plugin) {
		Bundle[] bundles = InternalPlatform.getDefault().getBundles(plugin, null);
		IPluginDescriptor[] results = new IPluginDescriptor[bundles.length];
		int added = 0;
		for (int i = 0; i < bundles.length; i++) {
			PluginDescriptor desc = getPluginDescriptor(bundles[i]);
			if (desc != null)
				results[added++] = desc;
		}
		if (added == bundles.length)
			return results;
		
		if (added == 0)
			return new IPluginDescriptor[0];
		
		IPluginDescriptor[] toReturn = new IPluginDescriptor[added];
		System.arraycopy(results, 0, toReturn, 0, added);
		return toReturn;
	}

	public IPluginDescriptor getPluginDescriptor(String pluginId, PluginVersionIdentifier version) {
		Bundle[] bundles = InternalPlatform.getDefault().getBundles(pluginId, version.toString());
		if (bundles == null)
			return null;
		
		return getPluginDescriptor(bundles[0]);
	}

	public IPluginDescriptor[] getPluginDescriptors() {
		Bundle[] bundles = InternalPlatform.getDefault().getBundleContext().getBundles();
		ArrayList pds = new ArrayList(bundles.length);
		for (int i = 0; i < bundles.length; i++) {
			boolean isFragment = InternalPlatform.getDefault().isFragment(bundles[i]);
			if (!isFragment && bundles[i].getSymbolicName() != null && (bundles[i].getState() == Bundle.RESOLVED || bundles[i].getState() == Bundle.STARTING || bundles[i].getState() == Bundle.ACTIVE))
				pds.add(getPluginDescriptor(bundles[i]));
		}
		IPluginDescriptor[] result = new IPluginDescriptor[pds.size()];
		return (IPluginDescriptor[]) pds.toArray(result);
	}

	void logError(IStatus status) {
		InternalPlatform.getDefault().log(status);
		if (InternalPlatform.DEBUG)
			System.out.println(status.getMessage());
	}

	public class RegistryListener implements BundleListener {
		public void bundleChanged(BundleEvent event) {
			synchronized(descriptors) {
				if (event.getType() == BundleEvent.UNINSTALLED || event.getType() == BundleEvent.UNRESOLVED) {
					descriptors.remove(event.getBundle());
				}
			}
		}
	}
}