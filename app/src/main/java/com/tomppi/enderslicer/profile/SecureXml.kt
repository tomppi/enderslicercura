package com.tomppi.enderslicer.profile

import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal object SecureXml {
    private const val FEATURE_DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl"
    private const val FEATURE_EXTERNAL_GENERAL = "http://xml.org/sax/features/external-general-entities"
    private const val FEATURE_EXTERNAL_PARAMETER = "http://xml.org/sax/features/external-parameter-entities"
    private const val FEATURE_LOAD_EXTERNAL_DTD = "http://apache.org/xml/features/nonvalidating/load-external-dtd"
    private const val PROPERTY_ACCESS_EXTERNAL_DTD = "http://javax.xml.XMLConstants/property/accessExternalDTD"
    private const val PROPERTY_ACCESS_EXTERNAL_SCHEMA = "http://javax.xml.XMLConstants/property/accessExternalSchema"

    fun factory(namespaceAware: Boolean = true): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = namespaceAware
        runCatching { isXIncludeAware = false }
        setRequiredFeature(FEATURE_DISALLOW_DOCTYPE, true, "DOCTYPE declarations")
        setRequiredFeature(FEATURE_EXTERNAL_GENERAL, false, "external general entities")
        setRequiredFeature(FEATURE_EXTERNAL_PARAMETER, false, "external parameter entities")
        setRequiredFeature(FEATURE_LOAD_EXTERNAL_DTD, false, "external DTD loading")
        setRequiredProperty(PROPERTY_ACCESS_EXTERNAL_DTD, "", "external DTD access")
        setRequiredProperty(PROPERTY_ACCESS_EXTERNAL_SCHEMA, "", "external schema access")
        runCatching { setExpandEntityReferences(false) }
        runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
    }

    private fun DocumentBuilderFactory.setRequiredFeature(feature: String, value: Boolean, label: String) {
        try {
            setFeature(feature, value)
            require(getFeature(feature) == value) {
                "XML parser did not apply the required $label hardening ($feature)"
            }
        } catch (error: Exception) {
            throw IllegalStateException(
                "XML parser does not support the required $label hardening ($feature)",
                error,
            )
        }
    }

    private fun DocumentBuilderFactory.setRequiredProperty(property: String, value: String, label: String) {
        try {
            setAttribute(property, value)
        } catch (error: Exception) {
            throw IllegalStateException(
                "XML parser does not support the required $label hardening ($property)",
                error,
            )
        }
    }
}
