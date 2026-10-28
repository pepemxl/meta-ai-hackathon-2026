'use strict';

// npm run user:add -- coach@example.com "Nombre" 'contraseña'
// Creates a coach in the MySQL database of .env (there is no public sign-up).

const { loadEnv, configFromEnv } = require('../config');
const { hashPassword } = require('../auth');
const { createMysqlRepo } = require('../repo/mysql');

async function main() {
  const [email, name, password] = process.argv.slice(2);
  if (!email || !name || !password) {
    console.error('Uso: npm run user:add -- correo "Nombre" \'contraseña\'');
    process.exit(2);
  }
  if (password.length < 10) {
    console.error('La contraseña debe tener al menos 10 caracteres');
    process.exit(2);
  }
  loadEnv();
  const repo = createMysqlRepo(configFromEnv().db);
  try {
    const id = await repo.createUser({ email, name, passwordHash: hashPassword(password) });
    console.log(`Usuario ${email} creado (coach_id u${id})`);
  } catch (e) {
    console.error(e.code === 'DUPLICATE' ? `Ya existe un usuario con ${email}` : `No se pudo crear: ${e.message}`);
    process.exitCode = 1;
  } finally {
    await repo.close();
  }
}

main();
