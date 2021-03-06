package net.jibini.check.resource.impl

import net.jibini.check.resource.Resource

import java.io.InputStream
import java.util.*

/**
 * A resource which is accessible through a given stream.
 *
 * @author Zach Goethel
 */
class ResourceImpl(
    override val stream: InputStream,

    override val uniqueIdentifier: String = "STREAM; ${UUID.randomUUID()}"
) : Resource()