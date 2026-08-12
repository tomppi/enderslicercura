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

    fun factory(namespaceAware: Boolean = true): DocumentBuilderFactory =
        factory(DocumentBuilderFactory.newInstance(), namespaceAware)

    internal fun factory(
        base: DocumentBuilderFactory,
        namespaceAware: Boolean = true,
    ): DocumentBuilderFactory = base.apply {
        isNamespaceAware = namespaceAware
        runCatching { isXIncludeAware = false }
        // Load-bearing JAXP/SAX protections, supported by every real parser
        // (JDK Xerces and Android Expat). A parser that cannot disable external
        // entities is genuinely unsafe, so these stay required.
        setRequiredFeature(FEATURE_EXTERNAL_GENERAL, false, "external general entities")
        setRequiredFeature(FEATURE_EXTERNAL_PARAMETER, false, "external parameter entities")
        setRequiredFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true, "secure processing")
        setRequiredProperty(PROPERTY_ACCESS_EXTERNAL_DTD, "", "external DTD access")
        setRequiredProperty(PROPERTY_ACCESS_EXTERNAL_SCHEMA, "", "external schema access")
        // Apache-Xerces-specific hardening. Android's Expat parser rejects the
        // feature name (it refuses DOCTYPE declarations natively), so these are
        // applied when the parser supports them and skipped otherwise.
        setAdaptiveFeature(FEATURE_DISALLOW_DOCTYPE, true, "DOCTYPE declarations")
        setAdaptiveFeature(FEATURE_LOAD_EXTERNAL_DTD, false, "external DTD loading")
        runCatching { setExpandEntityReferences(false) }
    }

    private fun DocumentBuilderFactory.setRequiredFeature(feature: String, value: Boolean, label: String) {
        val applied = runCatching {
            setFeature(feature, value)
            getFeature(feature) == value
        }
        when {
            applied.getOrNull() == true -> Unit
            applied.isFailure -> throw IllegalStateException(
                "XML parser does not support the required $label hardening ($feature)",
                applied.exceptionOrNull(),
            )
            else -> throw IllegalStateException(
                "XML parser did not apply the required $label hardening ($feature)",
            )
        }
    }

    private fun DocumentBuilderFactory.setAdaptiveFeature(feature: String, value: Boolean, label: String) {
        val applied = runCatching {
            setFeature(feature, value)
            getFeature(feature) == value
        }
        when {
            applied.getOrNull() == true -> Unit
            applied.isFailure -> Unit
            else -> throw IllegalStateException(
                "XML parser accepted but did not apply the $label hardening ($feature)",
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
