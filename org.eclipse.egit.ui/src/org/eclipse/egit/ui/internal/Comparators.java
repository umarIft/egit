/*******************************************************************************
 * Copyright (C) 2011, Dariusz Luksza <dariusz@luksza.org>
 * Copyright (C) 2011, 2013 Robin Stocker <robin@nibor.org>
 * Copyright (C) 2011, Bernard Leach <leachbj@bouncycastle.org>
 * Copyright (C) 2013, Michael Keppler <michael.keppler@gmx.de>
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.egit.ui.internal;

import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;

import org.eclipse.core.resources.IResource;
import org.eclipse.jface.util.Policy;
import org.eclipse.jgit.lib.Ref;

/**
 * Class containing comparators
 */
public class Comparators {

	private Comparators() {
		// non-instantiable utility class
	}

	/**
	 * Instance of comparator that sorts strings in ascending alphabetical and
	 * numerous order (also known as natural order), case insensitive.
	 * 
	 * The comparator is guaranteed to return a non-zero value if
	 * string1.equals(String2) returns false
	 */
	public static final Comparator<String> STRING_ASCENDING_COMPARATOR = new Comparator<String>() {
		public int compare(String o1, String o2) {
			if (o1.length() == 0 || o2.length() == 0)
				return o1.length() - o2.length();

			LinkedList<String> o1Parts = splitIntoDigitAndNonDigitParts(o1);
			LinkedList<String> o2Parts = splitIntoDigitAndNonDigitParts(o2);

			Iterator<String> o2PartsIterator = o2Parts.iterator();

			for (String o1Part : o1Parts) {
				if (!o2PartsIterator.hasNext())
					return 1;

				String o2Part = o2PartsIterator.next();

				int result;

				if (Character.isDigit(o1Part.charAt(0))
						&& Character.isDigit(o2Part.charAt(0))) {
					o1Part = stripLeadingZeros(o1Part);
					o2Part = stripLeadingZeros(o2Part);
					result = o1Part.length() - o2Part.length();
					if (result == 0)
						result = o1Part.compareToIgnoreCase(o2Part);
				} else {
					result = o1Part.compareToIgnoreCase(o2Part);
				}

				if (result != 0)
					return result;
			}

			if (o2PartsIterator.hasNext())
				return -1;
			else {
				// strings are equal (in the Object.equals() sense)
				// or only differ in case and/or leading zeros
				return o1.compareTo(o2);
			}
		}
	};

	/**
	 * Instance of comparator which sorts {@link Ref} names using
	 * {@link Comparators#STRING_ASCENDING_COMPARATOR}.
	 */
	public static final Comparator<Ref> REF_ASCENDING_COMPARATOR = new Comparator<Ref>() {
		public int compare(Ref o1, Ref o2) {
			return STRING_ASCENDING_COMPARATOR.compare(o1.getName(),
					o2.getName());
		}
	};

	/**
	 * Comparator for comparing {@link IResource} by the result of
	 * {@link IResource#getName()}.
	 */
	public static final Comparator<IResource> RESOURCE_NAME_COMPARATOR = new Comparator<IResource>() {
		public int compare(IResource r1, IResource r2) {
			return Policy.getComparator().compare(r1.getName(), r2.getName());
		}
	};

	private static LinkedList<String> splitIntoDigitAndNonDigitParts(
			String input) {
		LinkedList<String> parts = new LinkedList<String>();
		int partStart = 0;
		boolean previousWasDigit = Character.isDigit(input.charAt(0));
		for (int i = 1; i < input.length(); i++) {
			boolean isDigit = Character.isDigit(input.charAt(i));
			if (isDigit != previousWasDigit) {
				parts.add(input.substring(partStart, i));
				partStart = i;
				previousWasDigit = isDigit;
			}
		}
		parts.add(input.substring(partStart));
		return parts;
	}

	private static String stripLeadingZeros(String input) {
		for (int i = 0; i < input.length(); i++)
			if (input.charAt(i) != '0')
				return input.substring(i);
		return ""; //$NON-NLS-1$
	}
}
