Vagrant.configure("2") do |config|

  config.vm.box = "ubuntu/trusty64"
  config.vm.host_name = "hm-teller"
  config.ssh.forward_agent = true

  config.vm.provider :virtualbox do |vb|
    vb.name = Dir.pwd().split("/")[-1] + "-" + Time.now.to_f.to_i.to_s
    vb.customize ["modifyvm", :id, "--natdnshostresolver1", "on"]
    vb.customize [ "guestproperty", "set", :id, "--timesync-threshold", 10000 ]
    # Scala is memory-hungry
    vb.memory = 5120
    # vb.cpus = 4 # Uncomment to use more cores
  end

  config.vm.provision :shell do |sh|
    sh.path = "vagrant/up.bash"
  end

  config.vm.network :forwarded_port, guest: 9000, host: 9000

end
