/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.eclipse.lemminx.extensions.xerces;

import static org.eclipse.lemminx.dom.parser.Constants._CCB;
import static org.eclipse.lemminx.dom.parser.Constants._CMA;
import static org.eclipse.lemminx.dom.parser.Constants._CSB;
import static org.eclipse.lemminx.dom.parser.Constants._DQO;
import static org.eclipse.lemminx.dom.parser.Constants._SIQ;
import static org.eclipse.lemminx.dom.parser.Constants._SQO;
import static org.eclipse.lemminx.utils.StringUtils.getString;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.xerces.util.MessageFormatter;
import org.eclipse.lemminx.dom.parser.MultiLineStream;
import org.eclipse.lemminx.extensions.contentmodel.participants.XMLSchemaErrorCode;

/**
 * SchemaMessageProvider implements an XMLMessageProvider that provides
 * localizable error messages for the W3C XML Schema Language
 * 
 * @xerces.internal
 * 
 * @author Elena Litani, IBM
 * @version $Id: XSMessageFormatter.java 813087 2009-09-09 19:35:27Z mrglavas $
 * 
 *          Modified to use additional resource bundle
 * 
 * @author Red Hat Inc. <nkomonen@redhat.com>
 */
public class LSPMessageFormatter implements MessageFormatter {
	/**
	 * The domain of messages concerning the XML Schema: Structures specification.
	 */
	public static final String SCHEMA_DOMAIN = "http://www.w3.org/TR/xml-schema-1";

	private static final int[] SEPARATORS = new int[] { _DQO, _SQO, _SIQ };

	// private objects to cache the locale and resource bundle
	private Locale fLocale = null;
	private ResourceBundle fResourceBundle = null;
	private ResourceBundle newResourceBundle = null;

	/**
	 * Formats a message with the specified arguments using the given locale
	 * information.
	 * 
	 * @param locale    The locale of the message.
	 * @param key       The message key.
	 * @param arguments The message replacement text arguments. The order of the
	 *                  arguments must match that of the placeholders in the actual
	 *                  message.
	 * 
	 * @return Returns the formatted message.
	 *
	 * @throws MissingResourceException Thrown if the message with the specified key
	 *                                  cannot be found.
	 */
	public String formatMessage(Locale locale, String key, Object[] arguments) throws MissingResourceException {

		if (locale == null) {
			locale = Locale.getDefault();
		}
		if (locale != fLocale) {
			fResourceBundle = ResourceBundle.getBundle("org.apache.xerces.impl.msg.XMLSchemaMessages", locale);
			newResourceBundle = ResourceBundle.getBundle("XMLSchemaMessagesReformatted", locale); // in
																									// src/main/resources

			fLocale = locale;
		}

		boolean usedNewResourceBundle = false;

		String msg = null;

		if (newResourceBundle.containsKey(key)) {
			msg = newResourceBundle.getString(key);
			usedNewResourceBundle = true;
		} else {
			msg = fResourceBundle.getString(key);
		}

		if (arguments != null) {
			try {

				if (usedNewResourceBundle) {
					arguments = reformatSchemaArguments(XMLSchemaErrorCode.get(key), arguments);
				}

				msg = java.text.MessageFormat.format(msg, arguments);
			} catch (Exception e) {
				msg = fResourceBundle.getString("FormatFailed");
				msg += " " + fResourceBundle.getString(key);
			}
		}

		if (msg == null) {
			msg = fResourceBundle.getString("BadMessageKey");
			throw new MissingResourceException(msg, "XMLSchemaMessages", key);
		}

		return msg;
	}

	/**
	 * Modifies the schema message arguments to a cleaned down format
	 * 
	 * @param code
	 * @param message
	 * @return
	 */
	public static Object[] reformatSchemaArguments(XMLSchemaErrorCode code, Object[] arguments) {

		switch (code) {
		case cvc_complex_type_2_4_a:
			return cvc_2_4_a_solution(arguments);
		case cvc_complex_type_2_4_b:
			return cvc_2_4_b_solution(arguments);
		case cvc_enumeration_valid:
			return enumeration_valid_solution(arguments);
		default:
			return arguments;
		}
	}

	private static String reformatElementNames(String names) {
		// First pass: extract all element names
		List<String> elementNames = new ArrayList<>();
		MultiLineStream stream = new MultiLineStream(names, 0);

		while (!stream.eos()) {
			stream.advance(1); // Consume ' ' or '{' if first item
			boolean hasNamespace = stream.peekChar() == _DQO;
			if (hasNamespace) {
				stream.advance(1); // Consume "
				stream.advanceUntilAnyOfChars(SEPARATORS); // " | " | '
				stream.advance(2); // Consume quotation and ':'
			}
			StringBuilder nameBuilder = new StringBuilder();
			while (stream.peekChar() != _CCB && stream.peekChar() != _CMA) { // } | ,
				nameBuilder.append((char) stream.peekChar());
				stream.advance(1);
			}
			String name = nameBuilder.toString().trim();
			if (!name.isEmpty()) {
				elementNames.add(name);
			}
			stream.advance(1);
		}

		// Second pass: separate core mediators from connector operations (name contains '.')
		List<String> coreMediators = new ArrayList<>();
		Map<String, List<String>> connectorGroups = new LinkedHashMap<>();

		for (String name : elementNames) {
			int dotIndex = name.indexOf('.');
			if (dotIndex > 0) {
				String prefix = name.substring(0, dotIndex);
				String operation = name.substring(dotIndex + 1);
				connectorGroups.computeIfAbsent(prefix, k -> new ArrayList<>()).add(operation);
			} else {
				coreMediators.add(name);
			}
		}

		// Build result: core mediators individually, then grouped connector operations
		StringBuilder result = new StringBuilder();
		for (String mediator : coreMediators) {
			result.append(" - ").append(mediator).append("\n");
		}
		for (Map.Entry<String, List<String>> entry : connectorGroups.entrySet()) {
			List<String> ops = entry.getValue();
			result.append(" - ").append(entry.getKey()).append(".* (")
					.append(ops.size()).append(" operation")
					.append(ops.size() != 1 ? "s" : "").append(")\n");
		}
		return result.toString();
	}

	/**
	 * Reformats a string of format: "[name1, name2, name3]"
	 * 
	 * @param names
	 * @return
	 */
	private static String reformatArrayElementNames(String names) {
		StringBuilder sb = new StringBuilder();

		MultiLineStream stream = new MultiLineStream(names, 0);

		while (!stream.eos()) { // ]
			stream.advance(1);// Consume ' ' or '[' if first item
			sb.append(" - ");
			while (stream.peekChar() != _CSB && stream.peekChar() != _CMA) { // ] | ,
				sb.append((char) stream.peekChar());
				stream.advance(1);
			}
			sb.append("\n");
			stream.advance(1); // Consume , or ]

		}
		return sb.toString();
	}

	/**
	 * Returns a pattern matcher looking for a string that matches the format of:
	 * "{"http://maven.apache.org/POM/4.0.0":propertiesa}"
	 * 
	 * @param name
	 * @return
	 */
	private static Matcher getNamespaceMatcher(String name) {
		Pattern namespacePattern = Pattern.compile("^\\{\"(.*)\":(.*)(\\}|,)");
		return namespacePattern.matcher(name);
	}

	/**
	 * Parses the message for cvc.2.4.a and returns reformatted arguments
	 * 
	 * With Namespace
	 * 
	 * arguments[0]: {"http://maven.apache.org/POM/4.0.0":propertiesa} arguments[1]:
	 * {"http://maven.apache.org/POM/4.0.0":groupId,
	 * "http://maven.apache.org/POM/4.0.0":version}
	 * 
	 * Without Namespace
	 * 
	 * arguments[0]: propertiesa arguments[1]: {groupId, version}
	 * 
	 * @param arguments
	 * @return
	 */
	private static final String SYNAPSE_NAMESPACE = "http://ws.apache.org/ns/synapse";

	private static Object[] cvc_2_4_a_solution(Object[] arguments) {
		Matcher m = getNamespaceMatcher(getString(arguments[0]));
		String schema = null;
		String name = null;
		String validNames = null;

		if (m.matches()) {
			name = m.group(2);
			String namespace = m.group(1);
			// Strip Synapse namespace noise — it provides no useful info to the consumer
			schema = SYNAPSE_NAMESPACE.equals(namespace) ? "" : "{" + namespace + "}";
			validNames = reformatElementNames(getString(arguments[1]));
		} else { // No namespace, so just element name
			name = getString(arguments[0]);
			schema = "{the schema}";
			validNames = reformatElementNames(getString(arguments[1]));
		}
		return new Object[] { name, validNames, schema };
	}

	/**
	 * Parses the message for cvc.2.4.b and returns reformatted arguments
	 * 
	 * With Namespace
	 * 
	 * arguments[0]: elementName arguments[1]:
	 * {"http://maven.apache.org/POM/4.0.0":groupId,
	 * "http://maven.apache.org/POM/4.0.0":version}
	 * 
	 * Without Namespace
	 * 
	 * arguments[0]: elementName arguments[1]: {groupId, version}
	 * 
	 * @param arguments
	 * @return
	 */
	private static Object[] cvc_2_4_b_solution(Object[] arguments) {
		Matcher m = getNamespaceMatcher(getString(arguments[1]));

		String element = null;
		String missingChildElements = null;
		String schema = null;

		if (m.matches()) {
			missingChildElements = reformatElementNames(getString(arguments[1]));
			schema = "{" + m.group(1) + "}";
		} else {
			// No namespace, so just element name
			missingChildElements = reformatElementNames(getString(arguments[1]));
			schema = "{the schema}";
		}
		element = getString(arguments[0]);
		return new Object[] { element, missingChildElements, schema };
	}

	public static Object[] enumeration_valid_solution(Object[] arguments) {
		return new Object[] { getString(arguments[0]), reformatArrayElementNames(getString(arguments[1])) };
	}
}