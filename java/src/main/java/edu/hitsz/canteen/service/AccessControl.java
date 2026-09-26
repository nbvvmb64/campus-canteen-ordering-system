package edu.hitsz.canteen.service;

import edu.hitsz.canteen.exception.BusinessException;
import edu.hitsz.canteen.model.Role;
import edu.hitsz.canteen.model.User;
import edu.hitsz.canteen.model.UserStatus;
import edu.hitsz.canteen.persistence.CsvDatabase;

final class AccessControl {
    private AccessControl() {
    }

    static User requireRole(CsvDatabase database, User actor, Role expectedRole) {
        if (actor == null) {
            throw new BusinessException("请先登录");
        }
        User canonical = database.findUser(actor.getUserId())
                .orElseThrow(() -> new BusinessException("登录用户不存在"));
        if (!canonical.getUsername().equals(actor.getUsername())
                || canonical.getRole() != actor.getRole()) {
            throw new BusinessException("登录身份无效");
        }
        if (canonical.getUserStatus() != UserStatus.ACTIVE) {
            throw new BusinessException("账号已被禁用");
        }
        if (canonical.getRole() != expectedRole) {
            throw new BusinessException("权限不足：该操作仅允许" + roleText(expectedRole));
        }
        return canonical;
    }

    private static String roleText(Role role) {
        return role == Role.STUDENT ? "学生" : "商家";
    }
}
