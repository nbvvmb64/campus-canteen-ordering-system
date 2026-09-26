package edu.hitsz.canteen.web;

import edu.hitsz.canteen.exception.BusinessException;

public final class ForbiddenException extends BusinessException {
    public ForbiddenException(String message) { super(message); }
}
