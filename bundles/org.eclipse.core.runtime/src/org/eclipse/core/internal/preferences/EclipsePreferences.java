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
package org.eclipse.core.internal.preferences;

import java.io.*;
import java.util.*;
import org.eclipse.core.internal.runtime.InternalPlatform;
import org.eclipse.core.internal.runtime.Policy;
import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IScope;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

/**
 * @since 3.0
 */
public class EclipsePreferences implements IEclipsePreferences, IScope {

	public static final String DEFAULT_PREFERENCES_DIRNAME = ".preferences"; //$NON-NLS-1$
	public static final String DEFAULT_PREFERENCES_FILENAME = "prefs.ini"; //$NON-NLS-1$
	private static final String TRUE = "true"; //$NON-NLS-1$
	private static final String FALSE = "false"; //$NON-NLS-1$

	protected Properties properties;
	protected Map children;
	protected IEclipsePreferences parent;
	protected boolean removed = false;
	protected String name;
	protected ListenerList nodeListeners;
	protected ListenerList preferenceListeners;
	protected boolean isLoading = false;

	public EclipsePreferences() {
		this(null, null);
	}

	protected EclipsePreferences(IEclipsePreferences parent, String name) {
		super();
		this.parent = parent;
		this.name = name;
	}

	public interface INodeVisitor {
		public boolean visit(EclipsePreferences node);
	}

	public void accept(INodeVisitor visitor) {
		if (!visitor.visit(this))
			return;
		for (Iterator i = children.values().iterator(); i.hasNext();)
			((EclipsePreferences) i.next()).accept(visitor);
	}

	/*
	 * @see INodeChangeEvent
	 */
	public class NodeChangeEvent extends EventObject implements INodeChangeEvent {

		private Preferences child;

		protected NodeChangeEvent(Preferences parent, Preferences child) {
			super(parent);
			this.child = child;
		}

		/*
		 * @see org.eclipse.core.runtime.preferences.IEclipsePreferences.INodeChangeEvent#getParent()
		 */
		public Preferences getParent() {
			return (Preferences) getSource();
		}

		/*
		 * @see org.eclipse.core.runtime.preferences.IEclipsePreferences.INodeChangeEvent#getChild()
		 */
		public Preferences getChild() {
			return child;
		}
	}

	/*
	 * @see IPreferenceChangeEvent
	 */
	public class PreferenceChangeEvent extends EventObject implements IPreferenceChangeEvent {

		private String key;
		private Object newValue;
		public Object oldValue;

		protected PreferenceChangeEvent(Object node, String key, Object oldValue, Object newValue) {
			super(node);
			if (key == null)
				throw new IllegalArgumentException();
			this.key = key;
			this.newValue = newValue;
			this.oldValue = oldValue;
		}

		/*
		 * @see org.eclipse.core.runtime.preferences.IEclipsePreferences.IPreferenceChangeEvent#getKey()
		 */
		public String getKey() {
			return key;
		}

		/*
		 * @see org.eclipse.core.runtime.preferences.IEclipsePreferences.IPreferenceChangeEvent#getNewValue()
		 */
		public Object getNewValue() {
			return newValue;
		}

		/*
		 * @see org.eclipse.core.runtime.preferences.IEclipsePreferences.IPreferenceChangeEvent#getOldValue()
		 */
		public Object getOldValue() {
			return oldValue;
		}
	}

	protected void log(IStatus status) {
		InternalPlatform.getDefault().log(status);
	}

	public void load(IPath location) throws BackingStoreException {
		if (InternalPlatform.DEBUG_PREFERENCES)
			System.out.println("Loading preferences from file: " + location); //$NON-NLS-1$
		InputStream input = null;
		Properties fromDisk = new Properties();
		try {
			input = new BufferedInputStream(new FileInputStream(location.toFile()));
			fromDisk.load(input);
		} catch (FileNotFoundException e) {
			// file doesn't exist but that's ok.
			if (InternalPlatform.DEBUG_PREFERENCES)
				System.out.println("Preference file does not exist: " + location); //$NON-NLS-1$
			return;
		} catch (IOException e) {
			String message = Policy.bind("preferences.loadException", location.toString()); //$NON-NLS-1$
			log(new Status(IStatus.INFO, Platform.PI_RUNTIME, IStatus.INFO, message, e));
			throw new BackingStoreException(message);
		} finally {
			if (input != null)
				try {
					input.close();
				} catch (IOException e) {
					// ignore
				}
		}
		convertFromProperties(fromDisk);
	}

	public void save(IPath location) throws BackingStoreException {
		if (InternalPlatform.DEBUG_PREFERENCES)
			System.out.println("Saving preferences to file: " + location); //$NON-NLS-1$
		Properties table = convertToProperties(new Properties(), new Path(absolutePath()));
		if (table.isEmpty()) {
			// nothing to save. delete existing file if one exists.
			if (location.toFile().exists() && !location.toFile().delete()) {
				String message = Policy.bind("preferences.failedDelete", location.toString()); //$NON-NLS-1$
				log(new Status(IStatus.WARNING, Platform.PI_RUNTIME, IStatus.WARNING, message, null));
			}
			return;
		}
		OutputStream output = null;
		try {
			// create the parent dirs if they don't exist
			File parentFile = location.toFile().getParentFile();
			if (parentFile == null)
				return;
			parentFile.mkdirs();
			// set append to be false so we overwrite current settings.
			output = new BufferedOutputStream(new FileOutputStream(location.toFile(), false));
			table.store(output, null);
		} catch (IOException e) {
			String message = Policy.bind("preferences.saveException", location.toString()); //$NON-NLS-1$
			log(new Status(IStatus.ERROR, Platform.PI_RUNTIME, IStatus.ERROR, message, e));
			throw new BackingStoreException(message);
		} finally {
			if (output != null)
				try {
					output.close();
				} catch (IOException e) {
					// ignore
				}
		}
	}

	/*
	 * Convenience method for throwing an exception when methods
	 * are called on a removed node.
	 */
	protected void checkRemoved() {
		if (removed) {
			String message = Policy.bind("preferences.removedNode", name); //$NON-NLS-1$
			throw new IllegalStateException(message);
		}
	}

	/*
	 * @see org.osgi.service.prefs.Preferences#put(java.lang.String, java.lang.String)
	 */
	public void put(String key, String newValue) {
		// illegal state if this node has been removed
		checkRemoved();
		if (properties == null)
			properties = new Properties();
		String oldValue = properties.getProperty(key);
		properties.put(key, newValue);
		if (!newValue.equals(oldValue))
			preferenceChanged(key, oldValue, newValue);
	}

	/*
	 * @see org.osgi.service.prefs.Preferences#get(java.lang.String, java.lang.String)
	 */
	public String get(String key, String defaultValue) {
		// illegal state if this node has been removed
		checkRemoved();
		return properties == null ? defaultValue : properties.getProperty(key, defaultValue);
	}

	/*
	 * @see org.osgi.service.prefs.Preferences#remove(java.lang.String)
	 */
	public void remove(String key) {
		// illegal state if this node has been removed
		checkRemoved();
		if (properties == null)
			return;
		Object oldValue = properties.get(key);
		if (properties.size() == 0)
			properties = null;
		if (oldValue != null) {
			properties.remove(key);
			preferenceChanged(key, oldValue, null);
		}
	}

	/*
	 * @see org.osgi.service.prefs.Preferences#clear()
	 */
	public void clear() throws BackingStoreException {
		// illegal state if this node has been removed
		checkRemoved();
		if (properties == null)
			return;
		// call each one separately (instead of Properties.clear) so
		// clients get change notification
		for (Enumeration e = properties.keys(); e.hasMoreElements();)
			remove((String) e.nextElement());
		properties = null;
	}

	/*
	 * @see org.osgi.service.prefs.Preferences#putInt(java.lang.String, int)
	 */
	public void putInt(String key, int value) {
		// illegal state if this node has been removed
		checkRemoved();
		if (properties == null)
			properties = new Properties();
		String newValue = Integer.toString(value);
		Object oldValue = properties.getProperty(key);
		properties.put(key, newValue);
		if (!newValue.equals(oldValue)) {
			if (oldValue != null)
				try {
					oldValue = new Integer((String) oldValue);
				} catch (NumberFormatException e) {
					// ignore and let oldValue be a String
				}
			preferenceChanged(key, oldValue, new Integer(newValue));
		}
	}

	/*
	 * @see org.osgi.service.prefs.Preferences#getInt(java.lang.String, int)
	 */
	public int getInt(String key, int defaultValue) {
		// illegal state if this node has been removed
		checkRemoved();
		if (properties == null)
			return defaultValue;
		String value = properties.getProperty(key);
		int result = defaultValue;
		if (value != null)
			try {
				result = Integer.parseInt(value);
			} catch (NumberFormatException e) {
				// use default
			}
		return result;
	}

	/*
	 * @see org.osgi.service.prefs.Preferences#putLong(java.lang.String, long)
	 */
	public void putLong(String key, long value) {
		// illegal state if this node has been removed
		checkRemoved();
		if (properties == null)
			properties = new Properties();
		String newValue = Long.toString(value);
		Object oldValue = properties.getProperty(key);
		properties.put(key, newValue);
		if (!newValue.equals(oldValue)) {
			if (oldValue != null)
				try {
					oldValue = new Long((String) oldValue);
				} catch (NumberFormatException e) {
					// ignore and let oldValue be a String
				}
			preferenceChanged(key, oldValue, new Long(newValue));
		}
	}

	/*
	 * @see org.osgi.service.prefs.Preferences#getLong(java.lang.String, long)
	 */
	public long getLong(String key, long defaultValue) {
		// illegal state if this node has been removed
		checkRemoved();
		if (properties == null)
			return defaultValue;
		String value = properties.getProperty(key);
		long result = defaultValue;
		if (value != null)
			try {
				result = Long.parseLong(value);
			} catch (NumberFormatException e) {
				// use default
			}
		return result;
	}

	/*
	 * @see org.osgi.service.prefs.Preferences#putBoolean(java.lang.String, boolean)
	 */
	public void putBoolean(String key, boolean value) {
		// illegal state if this node has been removed
		checkRemoved();
		if (properties == null)
			properties = new Properties();
		String newValue = value ? TRUE : FALSE;
		String oldValue = properties.getProperty(key);
		properties.put(key, newValue);
		if (!newValue.equals(oldValue))
			preferenceChanged(key, oldValue == null ? null : new Boolean(oldValue), value ? Boolean.TRUE : Boolean.FALSE);
	}

	/*
	 * @see org.osgi.service.prefs.Preferences#getBoolean(java.lang.String, boolean)
	 */
	public boolean getBoolean(String key, boolean defaultValue) {
		// illegal state if this node has been removed
		checkRemoved();
		if (properties == null)
			return defaultValue;
		String value = properties.getProperty(key);
		return value == null ? defaultValue : TRUE.equalsIgnoreCase(value);
	}

	/*
	 * @see org.osgi.service.prefs.Preferences#putFloat(java.lang.String, float)
	 */
	public void putFloat(String key, float value) {
		// illegal state if this node has been removed
		checkRemoved();
		if (properties == null)
			properties = new Properties();
		String newValue = Float.toString(value);
		Object oldValue = properties.getProperty(key);
		properties.put(key, newValue);
		if (!newValue.equals(oldValue)) {
			if (oldValue != null)
				try {
					oldValue = new Float((String) oldValue);
				} catch (NumberFormatException e) {
					// ignore and let oldValue be a String
				}
			preferenceChanged(key, oldValue, new Float(newValue));
		}
	}

	/*
	 * @see org.osgi.service.prefs.Preferences#getFloat(java.lang.String, float)
	 */
	public float getFloat(String key, float defaultValue) {
		// illegal state if this node has been removed
		checkRemoved();
		if (properties == null)
			return defaultValue;
		String value = properties.getProperty(key);
		float result = defaultValue;
		if (value != null)
			try {
				result = Float.parseFloat(value);
			} catch (NumberFormatException e) {
				// use default
			}
		return result;
	}

	/*
	 * @see org.osgi.service.prefs.Preferences#putDouble(java.lang.String, double)
	 */
	public void putDouble(String key, double value) {
		// illegal state if this node has been removed
		checkRemoved();
		if (properties == null)
			properties = new Properties();
		String newValue = Double.toString(value);
		Object oldValue = properties.getProperty(key);
		properties.put(key, newValue);
		if (!newValue.equals(oldValue)) {
			if (oldValue != null)
				try {
					oldValue = new Double((String) oldValue);
				} catch (NumberFormatException e) {
					// ignore and let oldValue be a String
				}
			preferenceChanged(key, oldValue, new Double(newValue));
		}
	}

	/*
	 * @see org.osgi.service.prefs.Preferences#getDouble(java.lang.String, double)
	 */
	public double getDouble(String key, double defaultValue) {
		// illegal state if this node has been removed
		checkRemoved();
		if (properties == null)
			return defaultValue;
		String value = properties.getProperty(key);
		double result = defaultValue;
		if (value != null)
			try {
				result = Double.parseDouble(value);
			} catch (NumberFormatException e) {
				// use default
			}
		return result;
	}

	/*
	 * @see org.osgi.service.prefs.Preferences#putByteArray(java.lang.String, byte[])
	 */
	public void putByteArray(String key, byte[] value) {
		// illegal state if this node has been removed
		checkRemoved();
		if (properties == null)
			properties = new Properties();
		String newValue = null;
		String oldValue = properties.getProperty(key);
		newValue = new String(value);
		properties.put(key, newValue);
		// protect against NPE here. there is probably an easier way to do this
		if (!newValue.equals(oldValue))
			preferenceChanged(key, oldValue == null ? null : oldValue.getBytes(), value);
	}

	/*
	 * @see org.osgi.service.prefs.Preferences#getByteArray(java.lang.String, byte[])
	 */
	public byte[] getByteArray(String key, byte[] defaultValue) {
		// illegal state if this node has been removed
		checkRemoved();
		if (properties == null)
			return defaultValue;
		String value = properties.getProperty(key);
		return value == null ? defaultValue : value.getBytes();
	}

	/*
	 * @see org.osgi.service.prefs.Preferences#keys()
	 */
	public String[] keys() throws BackingStoreException {
		// illegal state if this node has been removed
		checkRemoved();
		if (properties == null || properties.size() == 0)
			return new String[0];
		return (String[]) new ArrayList(properties.keySet()).toArray(new String[properties.size()]);
	}

	/*
	 * @see org.osgi.service.prefs.Preferences#childrenNames()
	 */
	public String[] childrenNames() throws BackingStoreException {
		// illegal state if this node has been removed
		checkRemoved();
		if (children == null || children.size() == 0)
			return new String[0];
		return (String[]) new ArrayList(children.keySet()).toArray(new String[children.size()]);
	}

	/*
	 * @see org.osgi.service.prefs.Preferences#parent()
	 */
	public Preferences parent() {
		// illegal state if this node has been removed
		checkRemoved();
		return parent;
	}

	/*
	 * @see org.osgi.service.prefs.Preferences#node(java.lang.String)
	 */
	public Preferences node(String pathName) {
		return node(new Path(pathName));
	}

	/*
	 * @see org.eclipse.core.runtime.IEclipsePreferences#node(org.eclipse.core.runtime.IPath)
	 */
	public IEclipsePreferences node(IPath path) {
		if (path.isAbsolute())
			return Platform.getPreferencesService().getRootNode().node(path);

		// TODO: handle relative paths correctly (.. refs)

		// illegal state if this node has been removed
		checkRemoved();

		// short circuit this node
		if (path.isEmpty())
			return this;

		String key = path.segment(0);
		IEclipsePreferences child = null;
		if (children != null)
			child = (IEclipsePreferences) children.get(key);
		if (child == null) {
			child = ((PreferencesService) Platform.getPreferencesService()).getScope(this).create(this, key);
			if (children == null)
				children = new HashMap();
			children.put(key, child);
			// notify listeners
			nodeAdded(child);
		}
		return child.node(path.removeFirstSegments(1));
	}

	protected void nodeAdded(IEclipsePreferences child) {
		if (nodeListeners == null)
			return;
		Object[] listeners = nodeListeners.getListeners();
		for (int i = 0; i < listeners.length; i++)
			((INodeChangeListener) listeners[i]).added(new NodeChangeEvent(this, child));
	}

	protected void nodeRemoved(IEclipsePreferences child) {
		if (nodeListeners == null)
			return;
		Object[] listeners = nodeListeners.getListeners();
		for (int i = 0; i < listeners.length; i++)
			((INodeChangeListener) listeners[i]).removed(new NodeChangeEvent(this, child));
	}

	/*
	 * @see org.osgi.service.prefs.Preferences#nodeExists(java.lang.String)
	 */
	public boolean nodeExists(String pathName) throws BackingStoreException {
		return nodeExists(new Path(pathName));
	}

	/*
	 * @see org.eclipse.core.runtime.IEclipsePreferences#nodeExists(org.eclipse.core.runtime.IPath)
	 */
	public boolean nodeExists(IPath path) throws BackingStoreException {
		if (path.isAbsolute())
			return Platform.getPreferencesService().getRootNode().nodeExists(path);

		// TODO: handle relative paths correctly (.. refs)

		// short circuit for checking this node
		if (path.isEmpty())
			return !removed;
		// illegal state if this node has been removed
		checkRemoved();
		if (children == null)
			return false;
		String key = path.segment(0);
		IEclipsePreferences child = (IEclipsePreferences) children.get(key);
		if (child == null)
			return false;
		return child.nodeExists(path.removeFirstSegments(1));
	}

	/*
	 * @see org.osgi.service.prefs.Preferences#removeNode()
	 */
	public void removeNode() throws BackingStoreException {
		// illegal state if this node has been removed
		checkRemoved();
		// remove the node from the parent's collection and notify listeners
		if (parent instanceof EclipsePreferences) {
			removed = true;
			((EclipsePreferences) parent).removeNode(this);
		} else {
			String message = Policy.bind("preferences.invalidParentClass", absolutePath(), parent.getClass().getName()); //$NON-NLS-1$
			throw new BackingStoreException(message);
		}
		if (children != null) {
			Preferences[] nodes = (Preferences[]) children.values().toArray(new Preferences[children.size()]);
			for (int i = 0; i < nodes.length; i++)
				nodes[i].removeNode();
		}
	}

	/*
	 * Remove the child from the collection and notify the listeners.
	 */
	protected void removeNode(IEclipsePreferences child) {
		if (children != null)
			if (children.remove(child.name()) != null)
				nodeRemoved(child);
	}

	/*
	 * @see org.osgi.service.prefs.Preferences#name()
	 */
	public String name() {
		return name;
	}

	/*
	 * @see org.osgi.service.prefs.Preferences#absolutePath()
	 */
	public String absolutePath() {
		return parent == null ? Path.ROOT.toString() : new Path(parent.absolutePath()).append(name()).toString();
	}

	/*
	 * @see org.eclipse.core.runtime.IEclipsePreferences#addNodeChangeListener(org.eclipse.core.runtime.IEclipsePreferences.INodeChangeListener)
	 */
	public void addNodeChangeListener(INodeChangeListener listener) {
		checkRemoved();
		if (nodeListeners == null)
			nodeListeners = new ListenerList();
		nodeListeners.add(listener);
	}

	/*
	 * @see org.eclipse.core.runtime.IEclipsePreferences#addPreferenceChangeListener(org.eclipse.core.runtime.IEclipsePreferences.IPreferenceChangeListener)
	 */
	public void addPreferenceChangeListener(IPreferenceChangeListener listener) {
		checkRemoved();
		if (preferenceListeners == null)
			preferenceListeners = new ListenerList();
		preferenceListeners.add(listener);
	}

	/*
	 * @see org.eclipse.core.runtime.IEclipsePreferences#removeNodeChangeListener(org.eclipse.core.runtime.IEclipsePreferences.removeNodeChangeListener)
	 */
	public void removeNodeChangeListener(INodeChangeListener listener) {
		checkRemoved();
		if (nodeListeners == null)
			return;
		nodeListeners.remove(listener);
		if (nodeListeners.size() == 0)
			nodeListeners = null;
	}

	/*
	 * @see org.eclipse.core.runtime.IEclipsePreferences#removePreferenceChangeListener(org.eclipse.core.runtime.IEclipsePreferences.IPreferenceChangeListener)
	 */
	public void removePreferenceChangeListener(IPreferenceChangeListener listener) {
		checkRemoved();
		if (preferenceListeners == null)
			return;
		preferenceListeners.remove(listener);
		if (preferenceListeners.size() == 0)
			preferenceListeners = null;
	}

	/*
	 * Convenience method for notifying preference change listeners.
	 */
	protected void preferenceChanged(String key, Object oldValue, Object newValue) {
		if (preferenceListeners == null)
			return;
		Object[] listeners = preferenceListeners.getListeners();
		for (int i = 0; i < listeners.length; i++)
			((IPreferenceChangeListener) listeners[i]).preferenceChange(new PreferenceChangeEvent(this, key, oldValue, newValue));
	}

	public String toDeepDebugString() {
		return recursivePrint(this);
	}

	public String toString() {
		return absolutePath();
	}

	private String recursivePrint(Preferences preferences) {
		StringBuffer buffer = new StringBuffer();
		buffer.append(preferences.absolutePath());
		buffer.append('\n');
		try {
			String[] keys = preferences.keys();
			for (int i = 0; i < keys.length; i++) {
				buffer.append(preferences.absolutePath());
				buffer.append(IPath.SEPARATOR);
				buffer.append(keys[i]);
				buffer.append('=');
				buffer.append(preferences.get(keys[i], "*default*")); //$NON-NLS-1$
				buffer.append('\n');
			}
		} catch (BackingStoreException e) {
			System.out.println("Exception accessing keys."); //$NON-NLS-1$
			e.printStackTrace();
		}
		try {
			String[] childNames = preferences.childrenNames();
			for (int i = 0; i < childNames.length; i++)
				buffer.append(recursivePrint(preferences.node(childNames[i])));
		} catch (BackingStoreException e) {
			System.out.println("Exception accessing children names."); //$NON-NLS-1$
			e.printStackTrace();
		}
		return buffer.toString();
	}

	/*
	 * @see org.osgi.service.prefs.Preferences#flush()
	 */
	public void flush() throws BackingStoreException {
		// do nothing...subclasses to provide implementation
	}

	/*
	 * @see org.osgi.service.prefs.Preferences#sync()
	 */
	public void sync() throws BackingStoreException {
		// do nothing...subclasses to provide implementation
	}

	/*
	 * @see org.eclipse.core.runtime.preferences.IScope#create(org.eclipse.core.runtime.preferences.IEclipsePreferences)
	 */
	public IEclipsePreferences create(IEclipsePreferences nodeParent, String nodeName) {
		return new EclipsePreferences(nodeParent, nodeName);
	}

	private void convertFromProperties(Properties table) {
		IPath fullPath = new Path(absolutePath());
		for (Iterator i = table.keySet().iterator(); i.hasNext();) {
			String key = (String) i.next();
			String value = table.getProperty(key);
			if (value != null) {
				IPath childPath = new Path(key);
				if (childPath.segmentCount() > 0) {
					key = childPath.lastSegment();
					IPath child = childPath.removeLastSegments(1);
					// calculate the node relative to this node
					if (fullPath.isPrefixOf(childPath)) {
						child = child.removeFirstSegments(fullPath.segmentCount());
						node(child).put(key, value);
					} else {
						if (InternalPlatform.DEBUG_PREFERENCES)
							System.out.println("Ignoring value: " + value + " for key: " + childPath + " for node: " + fullPath); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					}
				}
			}
		}
	}

	/* 
	 * Helper method to convert this node to a Properties file suitable
	 * for persistence.
	 */
	private Properties convertToProperties(Properties result, IPath prefix) throws BackingStoreException {
		// add the key/value pairs from this node
		if (properties != null) {
			for (Iterator i = properties.keySet().iterator(); i.hasNext();) {
				String key = (String) i.next();
				String value = properties.getProperty(key, null);
				if (value != null)
					result.put(prefix.append(key).toString(), value);
			}
		}

		// recursively add the child information
		if (children != null) {
			for (Iterator i = children.values().iterator(); i.hasNext();) {
				EclipsePreferences child = (EclipsePreferences) i.next();
				child.convertToProperties(result, prefix.append(child.name()));
			}
		}
		return result;
	}

	protected boolean isLoading() {
		if (parent instanceof EclipsePreferences)
			return isLoading || ((EclipsePreferences) parent).isLoading();
		else
			return isLoading;
	}

}