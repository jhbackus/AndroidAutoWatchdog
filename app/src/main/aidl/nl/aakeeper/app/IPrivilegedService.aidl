package nl.aakeeper.app;

interface IPrivilegedService {
    String exec(String command);
    int uid();
    void destroy();
}
