-- A Google connection used to be shared by the whole workspace. On a public deployment
-- that means any signed-in visitor could make Gmail/Calendar calls with the account that
-- connected first. Remove that credential during deployment; it must not be reassigned to
-- an arbitrary user when per-user connection ownership is introduced later.
delete from connections where provider = 'google';
