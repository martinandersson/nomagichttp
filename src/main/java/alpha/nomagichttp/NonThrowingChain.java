package alpha.nomagichttp;

import alpha.nomagichttp.message.Response;

/**
 * A specialization of {@code Chain} that does not throw {@code Exception}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Chain
 */
public interface NonThrowingChain extends Chain
{
    /**
     * {@inheritDoc}
     * 
     * @return {@inheritDoc}
     * 
     * @throws UnsupportedOperationException
     *             {@inheritDoc}
     * @throws Exception
     *             {@inheritDoc}
     * 
     * @see Chain
     */
    @Override
    Response proceed();
}