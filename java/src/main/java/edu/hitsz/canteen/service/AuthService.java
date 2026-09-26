package edu.hitsz.canteen.service;

import edu.hitsz.canteen.exception.BusinessException;
import edu.hitsz.canteen.model.Role;
import edu.hitsz.canteen.model.User;
import edu.hitsz.canteen.model.UserStatus;
import edu.hitsz.canteen.persistence.CsvDatabase;
import edu.hitsz.canteen.security.PasswordHasher;
import edu.hitsz.canteen.util.DateTimes;
import edu.hitsz.canteen.util.Rules;

public final class AuthService {
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 128;

    private final CsvDatabase database;
    private final PasswordHasher passwordHasher;

    public AuthService(CsvDatabase database) {
        this.database = database;
        this.passwordHasher = new PasswordHasher();
    }

    public User register(String username, char[] password, Role role) {
        String normalizedUsername =
                Rules.requireTrimmedText(username, "账号", 3, 30);
        requirePassword(password);
        if (role == null) {
            throw new BusinessException("用户角色不能为空");
        }
        PasswordHasher.PasswordDigest digest = passwordHasher.hash(password);
        return database.transaction(() -> {
            if (database.findUserByUsername(normalizedUsername).isPresent()) {
                throw new BusinessException("账号已存在");
            }
            User user = new User(
                    database.nextUserId(),
                    normalizedUsername,
                    digest.getSalt(),
                    digest.getHash(),
                    role,
                    UserStatus.ACTIVE,
                    DateTimes.now());
            database.addUser(user);
            return user;
        });
    }

    public User login(String username, char[] password) {
        if (username == null || password == null) {
            throw new BusinessException("账号或密码错误");
        }
        User user = database.findUserByUsername(username)
                .orElseThrow(() -> new BusinessException("账号或密码错误"));
        if (user.getUserStatus() != UserStatus.ACTIVE) {
            throw new BusinessException("账号已被禁用");
        }
        if (!passwordHasher.verify(
                password, user.getPasswordSalt(), user.getPasswordHash())) {
            throw new BusinessException("账号或密码错误");
        }
        return user;
    }

    private void requirePassword(char[] password) {
        if (password == null
                || password.length < MIN_PASSWORD_LENGTH
                || password.length > MAX_PASSWORD_LENGTH) {
            throw new BusinessException(
                    "密码长度必须为" + MIN_PASSWORD_LENGTH + "～" + MAX_PASSWORD_LENGTH + "个字符");
        }
    }
}
