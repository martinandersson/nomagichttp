package alpha.nomagichttp;

import alpha.nomagichttp.message.Response;

/**
 * A specialization of {@code Chain} that does not throw {@code Exception}.
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
     * 
     * @see Chain
     */
    @Override
    Response proceed();
}